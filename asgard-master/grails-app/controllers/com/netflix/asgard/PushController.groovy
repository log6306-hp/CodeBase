/*
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.netflix.asgard.model.InstancePriceType
import com.netflix.asgard.push.CommonPushOptions
import com.netflix.asgard.push.PushException
import com.netflix.asgard.push.RollingPushOperation
import com.netflix.asgard.push.RollingPushOptions
import com.netflix.grails.contextParam.ContextParam
import java.rmi.NoSuchObjectException

@ContextParam('region')
class PushController {

	static allowedMethods = [startRolling: 'POST']

	static editActions = ['editRolling']

	def awsAutoScalingService
	def awsEc2Service
	def applicationService
	def pushService
	def spotInstanceRequestService
	def grailsApplication

	def index() {
		redirect(controller: "autoScaling", action: "list", params: params)
	}

	def editRolling() {
		UserContext userContext = UserContext.of(request)
		String name = params.id ?: params.name
		boolean showAllImages = params.allImages ? true : false

		Map attrs = [:]
		try {
			attrs = pushService.prepareEdit(userContext, name, showAllImages,
					Requests.ensureList(params.selectedSecurityGroups))
			attrs.putAll([
				pricing: params.pricing ?: attrs.pricing
			])
		} catch (NoSuchObjectException ignored) {
			Requests.renderNotFound('Auto Scaling Group', name, this)
			return
		} catch (PushException pe) {
			response.status = 404 // Not found
			flash.message = pe.message
			render(view: '/error/missing')
			return
		}
		attrs
	}

	def startRolling() {
		
		UserContext userContext = UserContext.of(request)
		List<String> selectedSecurityGroups = Requests.ensureList(params.selectedSecurityGroups)

		String groupName = params.name
		AutoScalingGroup group = awsAutoScalingService.getAutoScalingGroup(userContext, groupName)
		if (!group) {
			flash.message = "Auto scaling group '${groupName}' not found"
			redirect(action: 'result')
			return
		}

		Integer relaunchCount = params.relaunchCount?.toInteger() ?: 0
		Integer concurrentRelaunches = params.concurrentRelaunches?.toInteger() ?: 1
		relaunchCount = Ensure.bounded(0, relaunchCount, group.instances.size())
		concurrentRelaunches = Ensure.bounded(0, concurrentRelaunches, relaunchCount)

		String spotPrice = null
		if (params.pricing == InstancePriceType.SPOT.name()) {
			spotPrice = spotInstanceRequestService.recommendSpotPrice(userContext, params.instanceType)
		}

		RollingPushOptionsCreator crt = new RollingPushOptionsCreator(userContext, selectedSecurityGroups);
		RollingPushOptions pushOptions = crt.getAppropriateRollingOptions(relaunchCount, concurrentRelaunches, spotPrice);

		try {
			def pushOperation = pushService.startRollingPush(pushOptions)
			flash.message = "${pushOperation.task.name} has been started."
			redirect(controller: 'task', action: 'show', params: [id: pushOperation.taskId])
		} catch (Exception e) {
			flash.message = "Could not start push: ${e}"
			redirect(controller: "autoScaling", action: "show", params: [name: params.name])
		}
	}

	///A nested class created for removing code smell from startRolling!
	class RollingPushOptionsCreator {

		UserContext userContext
		List<String> selectedSecurityGroups

		RollingPushOptionsCreator(UserContext userCtx, List<String> selected_SecurityGroups ){
			this.userContext = userCtx
			this.selectedSecurityGroups = selected_SecurityGroups
		}

		public RollingPushOptions getAppropriateRollingOptions(Integer relaunchCount, Integer concurrentRelaunches, String spotPrice){
			RollingPushOptions pushOptions = new RollingPushOptions(
					common: new CommonPushOptions(
					userContext: userContext,
					checkHealth: params.containsKey('checkHealth'),
					afterBootWait: params.afterBootWait?.toInteger() ?: 30,
					appName: params.appName,
					env: grailsApplication.config.cloud.accountName,
					imageId: params.imageId,
					instanceType: params.instanceType,
					groupName: params.name,
					securityGroups: selectedSecurityGroups,
					maxStartupRetries: params.maxStartupRetries?.toInteger() ?: 5
					),
					newestFirst: params.newestFirst == 'true',
					relaunchCount: relaunchCount,
					concurrentRelaunches: concurrentRelaunches,
					rudeShutdown: params.containsKey('rudeShutdown'),
					iamInstanceProfile: params.iamInstanceProfile,
					spotPrice: spotPrice,
					keyName: params.keyName
					)
			return pushOptions;
		}
	}

	def result() {
		render view: '/common/result'
	}

	def enableTimeouts() {
		RollingPushOperation.timeoutsEnabled = true
		render "Push timeouts disabled"
	}

	def disableTimeouts() {
		RollingPushOperation.timeoutsEnabled = false
		render "Push timeouts enabled"
	}
}
