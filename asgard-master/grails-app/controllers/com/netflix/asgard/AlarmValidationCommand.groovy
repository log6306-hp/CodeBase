package com.netflix.asgard;

import com.netflix.asgard.model.MetricId

class AlarmValidationCommand {
	String alarmName
	String description

	String comparisonOperator
	String metric
	String namespace
	String existingMetric
	String statistic
	Integer period
	Integer evaluationPeriods
	Double threshold

	String topic
	String policy

	static constraints = {
		comparisonOperator(nullable: false, blank: false)
		threshold(nullable: false)
		metric(nullable: true, validator: { Object value, AlarmValidationCommand cmd ->
			(value && cmd.namespace) || cmd.existingMetric
		})
	}

	MetricId assembleMetric() {
		existingMetric ? MetricId.fromJson(existingMetric) : MetricId.from(namespace, metric)
	}
}