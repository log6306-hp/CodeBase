package com.netflix.asgard;

import com.netflix.asgard.model.AlarmData
import com.netflix.asgard.model.MetricId
import com.netflix.asgard.model.AlarmData.ComparisonOperator
import com.netflix.asgard.model.AlarmData.Statistic
import com.amazonaws.services.cloudwatch.model.MetricAlarm
import com.amazonaws.services.autoscaling.model.ScalingPolicy
import com.netflix.asgard.model.TopicData
import com.amazonaws.services.cloudwatch.model.Dimension

public class AlarmControllerHelper {

	def awsCloudWatchService
	UserContext userContext
	AlarmValidationCommand cmd

	public AlarmControllerHelper(def awsCloudWatchService_, UserContext userContext_,AlarmValidationCommand cmd_ ){
		this.awsCloudWatchService = awsCloudWatchService_
		this.userContext = userContext_
		this.cmd = cmd_
	}

	def getAlarm(def awsSnsService){
		MetricId metricId = cmd.assembleMetric()
		MetricAlarm alarm = awsCloudWatchService.getAlarm(userContext, cmd.alarmName)
		alarm.with {
			alarmName = cmd.alarmName
			alarmDescription = cmd.description
			comparisonOperator = cmd.comparisonOperator
			metricName = metricId.metricName
			namespace = metricId.namespace
			statistic = cmd.statistic
			period = cmd.period
			evaluationPeriods = cmd.evaluationPeriods
			threshold = cmd.threshold
		}
		awsCloudWatchService.getDimensionsForNamespace(getMetricId().namespace).each {
			String name = it
			String value = params[name]
			if (value) {
				alarm.dimensions << new Dimension(name: name, value: params[name])
			} else {
				Dimension removedDimension = alarm.dimensions.find { it.name == name }
				alarm.dimensions.remove(removedDimension)
			}
		}
		
		// The topic is optional, but if it is specified then it should exist.
		TopicData topic = awsSnsService.getTopic(userContext, cmd.topic)
		if (cmd.topic && !topic) {
			throw new IllegalStateException("Topic '${cmd.topic}' does not exist.")
		}
		String topicArn = topic?.arn ?: ''
	
		awsCloudWatchService.updateAlarm(userContext, AlarmData.fromMetricAlarm(alarm, topicArn))
		
		alarm
	}

	def getMetricId(){
		MetricId metricId = this.cmd.assembleMetric()
		return metricId
	}

	def getAlarmData(TopicData topic, ScalingPolicy policy){
		
		final ComparisonOperator comparisonOperator = cmd.comparisonOperator ?
				Enum.valueOf(ComparisonOperator, cmd.comparisonOperator) : null
		final Statistic statistic = cmd.statistic ? Enum.valueOf(Statistic, cmd.statistic) : Statistic.Average
		final UserContext userContext = UserContext.of(request)
		final Collection<String> snsArns = []
		MetricId metricId = getMetricId()

		if (cmd.topic && !topic) {
			throw new IllegalStateException("Topic '${cmd.topic}' does not exist.")
		}
		if (topic?.arn) {
			snsArns << topic.arn
		}
		if (cmd.policy && !policy) {
			throw new IllegalStateException("Scaling Policy '${cmd.policy}' does not exist.")
		}

		Map<String, String> dimensions = AlarmData.dimensionsForAsgName(policy?.autoScalingGroupName,
				awsCloudWatchService.getDimensionsForNamespace(metricId.namespace))
		final alarm = new AlarmData(
				description: cmd.description,
				comparisonOperator: comparisonOperator,
				metricName: metricId.metricName,
				namespace: metricId.namespace,
				statistic: statistic,
				period: cmd.period,
				evaluationPeriods: cmd.evaluationPeriods,
				threshold: cmd.threshold,
				actionArns: snsArns,
				dimensions: dimensions
				)
		String alarmName =  awsCloudWatchService.createAlarm(userContext, getAlarmData(topic, policy), policy.policyARN)
		alarmName
	}

	def save(def awsSnsService, def awsAutoScalingService){
		if (cmd.hasErrors()) {
			chain(action: 'create', model: [cmd: cmd], params: params)
		} else {
			final UserContext userContext = UserContext.of(request)
			TopicData topic = awsSnsService.getTopic(userContext, cmd.topic)
			ScalingPolicy policy = awsAutoScalingService.getScalingPolicy(userContext, cmd.policy)
			
			try {
				String alarmName = getAlarmData()
				flash.message = "Alarm '${alarmName}' has been created."
				redirect(action: 'show', params: [id: alarmName])
			} catch (Exception e) {
				flash.message = "Could not create Alarm for Scaling Policy '${cmd.policy}': ${e}"
				chain(action: 'create', model: [cmd: cmd], params: params)
			}
		}
	}
	
	def update(def awsSnsService){
		if (cmd.hasErrors()) {
			chain(action: 'edit', model: [cmd: cmd], params: params)
		} else {
			final UserContext userContext = UserContext.of(request)
			MetricAlarm alarm = getAlarm(awsSnsService)
			try {
				flash.message =  "Alarm '${alarm.alarmName}' has been updated."
				redirect(action: 'show', params: [id: alarm.alarmName])
			} catch (Exception e) {
				flash.message = "Could not update Alarm '${alarm.alarmName}': ${e}"
				chain(action: 'edit', model: [cmd: cmd], params: params)
			}
		}
	}
		
}
