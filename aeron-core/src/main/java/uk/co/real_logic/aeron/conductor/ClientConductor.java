/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.conductor;

import uk.co.real_logic.aeron.*;
import uk.co.real_logic.aeron.util.Agent;
import uk.co.real_logic.aeron.util.AgentIdleStrategy;
import uk.co.real_logic.aeron.util.AtomicArray;
import uk.co.real_logic.aeron.util.BufferRotationDescriptor;
import uk.co.real_logic.aeron.util.collections.EndPointMap;
import uk.co.real_logic.aeron.util.command.NewBufferMessageFlyweight;
import uk.co.real_logic.aeron.util.command.SubscriptionMessageFlyweight;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.broadcast.CopyBroadcastReceiver;
import uk.co.real_logic.aeron.util.concurrent.logbuffer.LogAppender;
import uk.co.real_logic.aeron.util.concurrent.logbuffer.LogReader;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBuffer;
import uk.co.real_logic.aeron.util.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.util.status.BufferPositionIndicator;
import uk.co.real_logic.aeron.util.status.PositionIndicator;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

import static uk.co.real_logic.aeron.util.BufferRotationDescriptor.BUFFER_COUNT;
import static uk.co.real_logic.aeron.util.command.ControlProtocolEvents.*;

/**
 * Client conductor takes responses and notifications from media driver and acts on them. As well as passes commands
 * to the media driver.
 */
public class ClientConductor extends Agent
{
    private static final int MAX_FRAME_LENGTH = 1024;

    public static final long AGENT_IDLE_MAX_SPINS = 5000;
    public static final long AGENT_IDLE_MAX_YIELDS = 100;
    public static final long AGENT_IDLE_MIN_PARK_NS = TimeUnit.NANOSECONDS.toNanos(10);
    public static final long AGENT_IDLE_MAX_PARK_NS = TimeUnit.MICROSECONDS.toNanos(100);

    private static final long NO_CORRELATION_ID = -1;

    private final RingBuffer commandBuffer;
    private final CopyBroadcastReceiver toClientBuffer;
    private final RingBuffer toDriverBuffer;

    private final BufferUsageStrategy bufferUsage;
    private final AtomicArray<Publication> publications = new AtomicArray<>();
    private final AtomicArray<Subscription> subscriptions;

    // Guarded by this
    private final EndPointMap<String, Publication> publicationMap = new EndPointMap<>();

    private final SubscriptionMap subscriptionMap = new SubscriptionMap();

    private final ConductorErrorHandler errorHandler;
    private final long awaitTimeout;

    private final SubscriptionMessageFlyweight subscriptionMessage = new SubscriptionMessageFlyweight();
    private final NewBufferMessageFlyweight newBufferMessage = new NewBufferMessageFlyweight();
    private final AtomicBuffer counterValuesBuffer;
    private final MediaDriverProxy mediaDriverProxy;
    private final Signal correlationSignal;

    // Guarded by this
    private Publication addedPublication;

    // Guarded by this
    private long activeCorrelationId;

    // Guarded by this
    private boolean hasRemovedPublication;

    public ClientConductor(final RingBuffer commandBuffer,
                           final CopyBroadcastReceiver toClientBuffer,
                           final RingBuffer toDriverBuffer,
                           final AtomicArray<Subscription> subscriptions,
                           final ConductorErrorHandler errorHandler,
                           final BufferUsageStrategy bufferUsageStrategy,
                           final AtomicBuffer counterValuesBuffer,
                           final MediaDriverProxy mediaDriverProxy,
                           final Signal correlationSignal,
                           final long awaitTimeout)
    {
        super(new AgentIdleStrategy(AGENT_IDLE_MAX_SPINS, AGENT_IDLE_MAX_YIELDS,
                AGENT_IDLE_MIN_PARK_NS, AGENT_IDLE_MAX_PARK_NS));

        this.counterValuesBuffer = counterValuesBuffer;

        this.correlationSignal = correlationSignal;
        this.mediaDriverProxy = mediaDriverProxy;
        this.commandBuffer = commandBuffer;
        this.toClientBuffer = toClientBuffer;
        this.toDriverBuffer = toDriverBuffer;
        this.bufferUsage = bufferUsageStrategy;
        this.subscriptions = subscriptions;
        this.errorHandler = errorHandler;
        this.awaitTimeout = awaitTimeout;

        hasRemovedPublication = false;
    }

    public boolean doWork()
    {
        boolean hasDoneWork = handleClientCommandBuffer();
        hasDoneWork |= handleMessagesFromMediaDriver();
        performBufferMaintenance();

        return hasDoneWork;
    }

    public void close()
    {
        stop();
        bufferUsage.close();
    }

    private void performBufferMaintenance()
    {
        publications.forEach(
            publication ->
            {
                long dirtyTermId = publication.dirtyTermId();
                if (dirtyTermId != Publication.NO_DIRTY_TERM)
                {
                    mediaDriverProxy.sendRequestTerm(
                        publication.destination(),
                        publication.sessionId(),
                        publication.channelId(),
                        dirtyTermId);
                }
            });

        subscriptions.forEach(Subscription::processBufferScan);
    }

    private boolean handleClientCommandBuffer()
    {
        final int messagesRead = commandBuffer.read(
            (msgTypeId, buffer, index, length) ->
            {
                switch (msgTypeId)
                {
                    case ADD_SUBSCRIPTION:
                    case REMOVE_SUBSCRIPTION:
                    {
                        subscriptionMessage.wrap(buffer, index);
                        final long[] channelIds = subscriptionMessage.channelIds();
                        final String destination = subscriptionMessage.destination();
                        if (msgTypeId == ADD_SUBSCRIPTION)
                        {
                            addSubscription(destination, channelIds);
                        }
                        else
                        {
                            removeSubscription(destination, channelIds);
                        }

                        toDriverBuffer.write(msgTypeId, buffer, index, length);
                        break;
                    }
                }
            }
        );

        return messagesRead > 0;
    }

    private void addSubscription(final String destination, final long[] channelIds)
    {
        for (final long channelId : channelIds)
        {
            subscriptions.forEach(
                (subscription) ->
                {
                    if (subscription.matches(destination, channelId))
                    {
                        subscriptionMap.put(destination, channelId, subscription);
                    }
                }
            );
        }
    }

    private void removeSubscription(final String destination, final long[] channelIds)
    {
        for (final long channelId : channelIds)
        {
            subscriptionMap.remove(destination, channelId);
        }
        // TODO: release buffers
    }

    private boolean handleMessagesFromMediaDriver()
    {
        final int messagesRead = toClientBuffer.receive(
            (msgTypeId, buffer, index, length) ->
            {
                try
                {
                    switch (msgTypeId)
                    {
                        case NEW_SUBSCRIPTION_BUFFER_EVENT:
                        case NEW_PUBLICATION_BUFFER_EVENT:
                            newBufferMessage.wrap(buffer, index);

                            final String destination = newBufferMessage.destination();
                            final long sessionId = newBufferMessage.sessionId();
                            final long channelId = newBufferMessage.channelId();
                            final long termId = newBufferMessage.termId();
                            final int positionIndicatorId = newBufferMessage.positionCounterId();

                            if (msgTypeId == NEW_PUBLICATION_BUFFER_EVENT)
                            {
                                if (newBufferMessage.correlationId() != activeCorrelationId)
                                {
                                    break;
                                }

                                onNewPublicationBuffers(destination, sessionId, channelId, termId, positionIndicatorId);
                            }
                            else
                            {
                                onNewSubscriptionBuffers(destination, sessionId, channelId, termId);
                            }
                            break;

                        case ERROR_RESPONSE:
                            errorHandler.onErrorResponse(buffer, index, length);
                            break;

                        default:
                            break;
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        );

        return messagesRead > 0;
    }

    private void onNewSubscriptionBuffers(final String destination, final long sessionId,
                                          final long channelId, final long termId)
    {
        onNewBuffers(sessionId,
                     channelId,
                     termId,
                     subscriptionMap.get(destination, channelId),
                     this::newReader,
                     LogReader[]::new,
                     (chan, buffers) ->
                     {
                         // TODO: get the counter id
                         chan.onBuffersMapped(sessionId, termId, buffers);
                     });
    }

    private void onNewPublicationBuffers(final String destination,
                                         final long sessionId,
                                         final long channelId,
                                         final long termId,
                                         final int positionIndicatorId) throws IOException
    {
        final LogAppender[] logs = new LogAppender[BUFFER_COUNT];
        for (int i = 0; i < BUFFER_COUNT; i++)
        {
            logs[i] = newAppender(i, sessionId, channelId, termId);
        }

        final PositionIndicator positionIndicator = new BufferPositionIndicator(counterValuesBuffer, positionIndicatorId);
        final Publication publication = new Publication(
            this, destination, channelId, sessionId, termId, logs, positionIndicator);
        publications.add(publication);
        addedPublication = publication;

        correlationSignal.signal();
    }

    public synchronized Publication addPublication(final String destination, final long channelId, final long sessionId)
    {
        Publication publication = publicationMap.get(destination, sessionId, channelId);

        if (publication == null)
        {
            activeCorrelationId = mediaDriverProxy.addPublication(destination, channelId, sessionId);

            final long startTime = System.currentTimeMillis();
            while (addedPublication == null)
            {
                correlationSignal.await(awaitTimeout);

                checkMediaDriverTimeout(startTime);
            }

            publication = addedPublication;
            publicationMap.put(destination, sessionId, channelId, publication);
            addedPublication = null;
            activeCorrelationId = NO_CORRELATION_ID;
        }

        publication.incRef();

        return publication;
    }

    public synchronized void releasePublication(final Publication publication)
    {
        final String destination = publication.destination();
        final long channelId = publication.channelId();
        final long sessionId = publication.sessionId();

        activeCorrelationId = mediaDriverProxy.removePublication(destination, channelId, sessionId);

        // TODO: wait for response from media driver

        // TODO:
        // bufferUsage.releasePublisherBuffers(destination, channelId, sessionId);
    }

    private void checkMediaDriverTimeout(final long startTime)
        throws MediaDriverTimeoutException
    {
        if (System.currentTimeMillis() - startTime > awaitTimeout)
        {
            String msg = String.format("No response from media driver within %d ms", awaitTimeout);
            throw new MediaDriverTimeoutException(msg);
        }
    }

    private interface LogFactory<L>
    {
        public L make(final int index, final long sessionId,
                      final long channelId, final long termId) throws IOException;
    }

    private <C extends ChannelEndpoint, L> void onNewBuffers(final long sessionId,
                                                             final long channelId,
                                                             final long termId,
                                                             final C channelEndpoint,
                                                             final LogFactory<L> logFactory,
                                                             final IntFunction<L[]> logArray,
                                                             final BiConsumer<C, L[]> notifier)
    {
        try
        {
            if (channelEndpoint == null)
            {
                // The new newBuffer refers to another client process, we can safely ignore it
                return;
            }

            if (!channelEndpoint.hasTerm(sessionId))
            {
                final L[] logs = logArray.apply(BUFFER_COUNT);
                for (int i = 0; i < BUFFER_COUNT; i++)
                {
                    logs[i] = logFactory.make(i, sessionId, channelId, termId);
                }

                notifier.accept(channelEndpoint, logs);
            }
            else
            {
                // TODO is this an error, or a reasonable case?
            }
        }
        catch (final Exception ex)
        {
            // TODO: establish correct client error handling strategy
            ex.printStackTrace();
        }
    }

    private AtomicBuffer newBuffer(final NewBufferMessageFlyweight newBufferMessage, final int index)
        throws IOException
    {
        final String location = newBufferMessage.location(index);
        final int offset = newBufferMessage.bufferOffset(index);
        final int length = newBufferMessage.bufferLength(index);

        return bufferUsage.newBuffer(location, offset, length);
    }

    private LogAppender newAppender(final int index,
                                    final long sessionId,
                                    final long channelId,
                                    final long termId) throws IOException
    {
        final AtomicBuffer logBuffer = newBuffer(newBufferMessage, index);
        final AtomicBuffer stateBuffer = newBuffer(newBufferMessage, index + BufferRotationDescriptor.BUFFER_COUNT);
        final byte[] header = DataHeaderFlyweight.createDefaultHeader(sessionId, channelId, termId);

        return new LogAppender(logBuffer, stateBuffer, header, MAX_FRAME_LENGTH);
    }

    private LogReader newReader(final int index, final long sessionId,
                                final long channelId, final long termId) throws IOException
    {
        final AtomicBuffer logBuffer = newBuffer(newBufferMessage, index);
        final AtomicBuffer stateBuffer = newBuffer(newBufferMessage, index + BufferRotationDescriptor.BUFFER_COUNT);

        return new LogReader(logBuffer, stateBuffer);
    }
}