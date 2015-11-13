package com.netflix.asgard

import java.util.Map;

 class RequestsHelper {

	
	static String valueInstanceOfMap(def output, def spaces, def indent, Map value) {
		output += '[\n'
		value.each { k, v -> output += prettyPrint(v, indent + 5, k) }
		output += "${spaces}]\n"
		output
	}
	
	static String valueInstanceOfCollection(def output, def spaces, def indent, Map value) {
		 output += '[\n'
		 value.each { it -> output += prettyPrint(it, indent + 5) }
		 output += "${spaces}]\n"
		 output
	}
	
	static String notValueInstanceOfCollection(def value, def output) {
		if (value?.hasProperty('name') && value?.hasProperty('value')) {
			output += "${value.name}=${value.value}"
		} else {
			output += value
		}
		output += ';\n'
	}
}
