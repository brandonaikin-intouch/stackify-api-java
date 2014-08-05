/*
 * Copyright 2014 Stackify
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
package com.stackify.api.common.log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Queue;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.stackify.api.AppIdentity;
import com.stackify.api.EnvironmentDetail;
import com.stackify.api.LogMsg;
import com.stackify.api.LogMsgGroup;
import com.stackify.api.common.AppIdentityService;
import com.stackify.api.common.http.HttpException;

/**
 * LogCollector
 * @author Eric Martin
 */
public class LogCollector {
		
	/**
	 * The logger (project) name
	 */
	private final String logger;
	
	/**
	 * Environment details
	 */
	private final EnvironmentDetail envDetail;
	
	/**
	 * Application identity service
	 */
	private final AppIdentityService appIdentityService;
	
	/**
	 * The queue of objects to be transmitted
	 */
	private final Queue<LogMsg> queue = Queues.synchronizedQueue(EvictingQueue.<LogMsg>create(1000)); 

	/**
	 * Constructor
	 * @param logger The logger (project) name
	 * @param envDetail Environment details
	 */
	public LogCollector(final String logger, final EnvironmentDetail envDetail, final AppIdentityService appIdentityService) {
		Preconditions.checkNotNull(logger);
		Preconditions.checkNotNull(envDetail);
		Preconditions.checkNotNull(appIdentityService);

		this.logger = logger;
		this.envDetail = envDetail;
		this.appIdentityService = appIdentityService;
	}
	
	/**
	 * Queues logMsg to be sent
	 * @param logMsg The log message
	 */
	public void addLogMsg(final LogMsg logMsg) {
		Preconditions.checkNotNull(logMsg);		
		queue.offer(logMsg);
	}
	
	/**
	 * Flushes the queue by sending all messages to Stackify
	 * @param sender The LogMsgGroup sender
	 * @return The number of messages sent to Stackify
	 * @throws IOException
	 * @throws HttpException 
	 */
	public int flush(final LogSender sender) throws IOException, HttpException {

		int numSent = 0;
		int maxToSend = queue.size();
		
		if (0 < maxToSend) {
		
			Optional<AppIdentity> appIdentity = appIdentityService.getAppIdentity();
			
			while (numSent < maxToSend) {
				
				// get the next batch of messages
				
				int batchSize = Math.min(maxToSend - numSent, 20);
	
				List<LogMsg> batch = Lists.newArrayListWithCapacity(batchSize);
				
				for (int i = 0; i < batchSize; ++i) {
					batch.add(queue.remove());
				}
				
				// build the log message group testAddAndFlushrecord
				
				LogMsgGroup.Builder groupBuilder = LogMsgGroup.newBuilder();
				
				groupBuilder.platform("java");
				groupBuilder.logger(logger);
				groupBuilder.serverName(envDetail.getDeviceName());
				
				if (appIdentity.isPresent()) {
					groupBuilder.cdId(appIdentity.get().getDeviceId());
					groupBuilder.cdAppId(appIdentity.get().getDeviceAppId());
					groupBuilder.appNameId(appIdentity.get().getAppNameId());
					groupBuilder.envId(appIdentity.get().getEnvId());
				} else {
					groupBuilder.appName(envDetail.getConfiguredAppName());
					groupBuilder.appLoc(envDetail.getAppLocation());
					groupBuilder.env(envDetail.getConfiguredEnvironmentName());
				}
	
				groupBuilder.msgs(batch);
				
				LogMsgGroup group = groupBuilder.build();
				
				// send the batch to Stackify
				
				int httpStatus = sender.send(group);	
				
				// if the batch failed to transmit, return the appropriate transmission status
				
				if (httpStatus != HttpURLConnection.HTTP_OK) {
					throw new HttpException(httpStatus);
				}
				
				// next iteration
				
				numSent += batchSize;
			}
		}
		
		return numSent;
	}
}