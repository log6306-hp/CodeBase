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

import com.amazonaws.services.autoscaling.model.ScalingPolicy
import com.amazonaws.services.cloudwatch.model.Dimension
import com.amazonaws.services.cloudwatch.model.MetricAlarm
import com.netflix.asgard.model.AlarmData
import com.netflix.asgard.model.AlarmData.ComparisonOperator
import com.netflix.asgard.model.AlarmData.Statistic
import com.netflix.asgard.model.MetricId
import com.netflix.asgard.model.TopicData
import com.netflix.grails.contextParam.ContextParam

import grails.converters.JSON
import grails.converters.XML

@ContextParam('region')
class AlarmController {

	def awsCloudWatchService
	def awsSnsService
	def awsAutoScalingService

	def allowedMethods = [save: 'POST', update: 'POST', delete: 'POST']

	def index() {
		redirect(action: 'list', params: params)
	}

	def list() {
		UserContext userContext = UserContext.of(request)
		List<MetricAlarm> alarms =
				(awsCloudWatchService.getAllAlarms(userContext) as List).sort { it.alarmName?.toLowerCase() }
		Map details = ['alarms': alarms]
		withFormat {
			html { details }
			xml { new XML(details).render(response) }
			json { new JSON(details).render(response) }
		}
	}

	def create() {
		String policyName = params.id ?: params.policy
		UserContext userContext = UserContext.of(request)
		ScalingPolicy policy = awsAutoScalingService.getScalingPolicy(userContext, policyName)
		if (!policy) {
			flash.message = "Policy '${policyName}' does not exist."
			redirect(action: 'result')
			return
		}
		awsCloudWatchService.prepareForAlarmCreation(userContext, params) <<
				[ policy: policyName ]
	}

	def show() {
		UserContext userContext = UserContext.of(request)
		String alarmName = params.id
		MetricAlarm alarm = awsCloudWatchService.getAlarm(userContext, alarmName)
		if (!alarm) {
			Requests.renderNotFound('Alarm', alarmName, this)
		} else {
			alarm.alarmActions.sort()
			alarm.getOKActions().sort()
			alarm.insufficientDataActions.sort()
			List<String> policies = AlarmData.fromMetricAlarm(alarm).policyNames
			Map result = [alarm: alarm, policies: policies]
			withFormat {
				html { return result }
				xml { new XML(result).render(response) }
				json { new JSON(result).render(response) }
			}
		}
	}

  def edit() {
	UserContext userContext = UserContext.of(request)
		String alarmName = params.id ?: params.alarmName
		MetricAlarm alarm = awsCloudWatchService.getAlarm(UserContext.of(request), alarmName)
		if (!alarm) {
			flash.message = "Alarm '${alarmName}' does not exist."
			redirect(action: 'result')
			return
		}
		AlarmData alarmData = AlarmData.fromMetricAlarm(alarm)
		awsCloudWatchService.prepareForAlarmCreation(UserContext.of(request), params, alarmData) <<
				[ policy: params.policy, alarmName: alarmName ]
	}

	def delete() {
		final String alarmName = params.id
		final MetricAlarm alarm = awsCloudWatchService.getAlarm(UserContext.of(request), alarmName)
		if (alarm) {
			awsCloudWatchService.deleteAlarms(UserContext.of(request), [alarmName])
			List<String> policies = AlarmData.fromMetricAlarm(alarm).policyNames
			flash.message = "Alarm ${alarmName} has been deleted."
			chooseRedirect(policies)
		} else {
			Requests.renderNotFound('Alarm', alarmName, this)
		}
	}

	private void chooseRedirect(List<String> policies) {
		Map destination = [action: 'result']
		if (policies?.size() == 1) {
			destination = [controller: 'scalingPolicy', action: 'show', id: policies[0]]
		}
		redirect destination
	}

	def result() {
		render view: '/common/result'
	}

	def save(AlarmValidationCommand cmd) {
		AlarmControllerHelper alch = new AlarmControllerHelper(awsCloudWatchService, UserContext.of(request), cmd)
		alch.save(awsSnsService, awsAutoScalingService)
	}

	def update(AlarmValidationCommand cmd) {
		AlarmControllerHelper alch = new AlarmControllerHelper(awsCloudWatchService, UserContext.of(request), cmd)
		alch.update(awsSnsService)
	}

	def setState() {
		String alarm = params.alarm
		String state = params.state
		UserContext userContext = UserContext.of(request)
		awsCloudWatchService.setAlarmState(userContext, alarm, state)
		redirect(action: 'show', params: [id: alarm])

	}
}


