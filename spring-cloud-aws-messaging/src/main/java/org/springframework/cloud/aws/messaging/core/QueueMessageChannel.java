/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.aws.messaging.core;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;
import org.springframework.util.NumberUtils;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.cloud.aws.messaging.core.QueueMessageUtils.createMessage;

/**
 * @author Agim Emruli
 * @author Alain Sahli
 * @since 1.0
 */
public class QueueMessageChannel extends AbstractMessageChannel implements PollableChannel {

	static final String ATTRIBUTE_NAMES = "All";
	public static final String MESSAGE_ATTRIBUTE_NAMES = "All";
	private final AmazonSQS amazonSqs;
	private final String queueUrl;

	public QueueMessageChannel(AmazonSQS amazonSqs, String queueUrl) {
		this.amazonSqs = amazonSqs;
		this.queueUrl = queueUrl;
	}

	@Override
	protected boolean sendInternal(Message<?> message, long timeout) {
		try {
			SendMessageRequest sendMessageRequest = new SendMessageRequest(this.queueUrl, String.valueOf(message.getPayload())).withDelaySeconds(getDelaySeconds(timeout));
			Map<String, MessageAttributeValue> messageAttributes = getMessageAttributes(message);
			if (!messageAttributes.isEmpty()) {
				sendMessageRequest.withMessageAttributes(messageAttributes);
			}
			this.amazonSqs.sendMessage(sendMessageRequest);
		} catch (AmazonServiceException e) {
			throw new MessageDeliveryException(message, e.getMessage(), e);
		}

		return true;
	}

	private Map<String, MessageAttributeValue> getMessageAttributes(Message<?> message) {
		HashMap<String, MessageAttributeValue> messageAttributes = new HashMap<>();
		for (Map.Entry<String, Object> messageHeader : message.getHeaders().entrySet()) {
			String messageHeaderName = messageHeader.getKey();
			Object messageHeaderValue = messageHeader.getValue();

			if (MessageHeaders.CONTENT_TYPE.equals(messageHeaderName) && messageHeaderValue != null) {
				messageAttributes.put(messageHeaderName, getContentTypeMessageAttribute(messageHeaderValue));
			} else if (messageHeaderValue instanceof String) {
				messageAttributes.put(messageHeaderName, getStringMessageAttribute((String) messageHeaderValue));
			} else if (messageHeaderValue instanceof Number) {
				messageAttributes.put(messageHeaderName, getNumberMessageAttribute(messageHeaderValue));
			} else if (messageHeaderValue instanceof ByteBuffer) {
				messageAttributes.put(messageHeaderName, getBinaryMessageAttribute((ByteBuffer) messageHeaderValue));
			} else {
				this.logger.warn(String.format("Message header with name '%s' and type '%s' cannot be sent as" +
								" message attribute because it is not supported by SQS.", messageHeaderName,
						messageHeaderValue != null ? messageHeaderValue.getClass().getName() : ""));
			}
		}

		return messageAttributes;
	}

	private MessageAttributeValue getBinaryMessageAttribute(ByteBuffer messageHeaderValue) {
		return new MessageAttributeValue().withDataType(MessageAttributeDataTypes.BINARY).withBinaryValue(messageHeaderValue);
	}

	private MessageAttributeValue getContentTypeMessageAttribute(Object messageHeaderValue) {
		if (messageHeaderValue instanceof MimeType) {
			return new MessageAttributeValue().withDataType(MessageAttributeDataTypes.STRING).withStringValue(messageHeaderValue.toString());
		} else if (messageHeaderValue instanceof String) {
			return new MessageAttributeValue().withDataType(MessageAttributeDataTypes.STRING).withStringValue((String) messageHeaderValue);
		}
		return null;
	}

	private MessageAttributeValue getStringMessageAttribute(String messageHeaderValue) {
		return new MessageAttributeValue().withDataType(MessageAttributeDataTypes.STRING).withStringValue(messageHeaderValue);
	}

	private MessageAttributeValue getNumberMessageAttribute(Object messageHeaderValue) {
		Assert.isTrue(NumberUtils.STANDARD_NUMBER_TYPES.contains(messageHeaderValue.getClass()), "Only standard number types are accepted as message header.");

		return new MessageAttributeValue().withDataType(MessageAttributeDataTypes.NUMBER + "." + messageHeaderValue.getClass().getName()).withStringValue(messageHeaderValue.toString());
	}

	@Override
	public Message<String> receive() {
		return this.receive(0);
	}

	@Override
	public Message<String> receive(long timeout) {
		ReceiveMessageResult receiveMessageResult = this.amazonSqs.receiveMessage(
				new ReceiveMessageRequest(this.queueUrl).
						withMaxNumberOfMessages(1).
						withWaitTimeSeconds(Long.valueOf(timeout).intValue()).
						withAttributeNames(ATTRIBUTE_NAMES).
						withMessageAttributeNames(MESSAGE_ATTRIBUTE_NAMES));
		if (receiveMessageResult.getMessages().isEmpty()) {
			return null;
		}
		com.amazonaws.services.sqs.model.Message amazonMessage = receiveMessageResult.getMessages().get(0);
		Message<String> message = createMessage(amazonMessage);
		this.amazonSqs.deleteMessage(new DeleteMessageRequest(this.queueUrl, amazonMessage.getReceiptHandle()));
		return message;
	}

	// returns 0 if there is a negative value for the delay seconds
	private static int getDelaySeconds(long timeout) {
		return Math.max(Long.valueOf(timeout).intValue(), 0);
	}
}
