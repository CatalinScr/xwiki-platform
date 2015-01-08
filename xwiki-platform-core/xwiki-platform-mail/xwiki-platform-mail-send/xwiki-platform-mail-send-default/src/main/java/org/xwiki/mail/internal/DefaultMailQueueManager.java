/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.mail.internal;

import java.util.Iterator;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

/**
 * Handles all operations on the Mail Sending Queue.
 *
 * @version $Id$
 * @since 6.4M3
 */
@Component
@Singleton
public class DefaultMailQueueManager implements MailQueueManager
{
    @Inject
    private Logger logger;

    /**
     * The Mail queue that the mail sender thread will use to send mails. We use a separate thread to allow sending
     * mail asynchronously.
     */
    private Queue<MailSenderQueueItem> mailQueue = new ConcurrentLinkedQueue<>();

    /**
     * @return the mail queue containing all pending mails to be sent
     */
    private Queue<MailSenderQueueItem> getMailQueue()
    {
        return this.mailQueue;
    }

    @Override
    public void addToQueue(MailSenderQueueItem mailSenderQueueItem)
    {
        this.mailQueue.add(mailSenderQueueItem);
    }

    @Override
    public boolean hasMessageToSend()
    {
        return !this.mailQueue.isEmpty();
    }

    @Override
    public MailSenderQueueItem peekMessage()
    {
        return this.mailQueue.peek();
    }

    @Override
    public boolean removeMessageFromQueue(MailSenderQueueItem mailSenderQueueItem)
    {
        return this.mailQueue.remove(mailSenderQueueItem);
    }

    @Override
    public void waitTillSent(UUID batchId, long timeout)
    {
        long startTime = System.currentTimeMillis();
        while (!isProcessed(batchId) && System.currentTimeMillis() - startTime < timeout) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                // Ignore but consider that the mail was sent
                this.logger.warn("Interrupted while waiting for mails to be sent. Reason [{}]",
                    ExceptionUtils.getRootCauseMessage(e));
                break;
            }
        }
    }

    @Override
    public boolean isProcessed(UUID batchId)
    {
        Iterator<MailSenderQueueItem> iterator = getMailQueue().iterator();
        while (iterator.hasNext()) {
            MailSenderQueueItem item = iterator.next();
            if (batchId == item.getBatchId()) {
                return false;
            }
        }
        return true;
    }
}
