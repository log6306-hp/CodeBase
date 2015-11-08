package com.netflix.asgard;

import grails.converters.JSON
import grails.converters.XML
import org.codehaus.groovy.grails.web.binding.DataBindingUtils
import org.codehaus.groovy.grails.web.json.JSONElement

class ImageDeleteCommand {
    String id
    AwsAutoScalingService awsAutoScalingService
    AwsEc2Service awsEc2Service
    RestClientService restClientService
    ConfigService configService
    def grailsApplication

    @SuppressWarnings("GroovyAssignabilityCheck")
    static constraints = {
        id(nullable: false, blank: false, size: 12..12, validator: { String value, ImageDeleteCommand command ->
            UserContext userContext = UserContext.of(Requests.request)
            List<String> promotionTargetServerRootUrls = configService.promotionTargetServerRootUrls
            String promotionTargetServer = command.grailsApplication.config.promote.targetServer
            String env = command.grailsApplication.config.cloud.accountName

            // If AMI is in use by a launch config or instance in the current region-env then report those references.
            Collection<String> instances = command.awsEc2Service.
                    getInstancesUsingImageId(userContext, value).collect { it.instanceId }
            Collection<String> launchConfigurations = command.awsAutoScalingService.
                    getLaunchConfigurationsUsingImageId(userContext, value).collect { it.launchConfigurationName }
            if (instances || launchConfigurations) {
                String reason = constructReason(instances, launchConfigurations)
                return ['image.imageId.used', value, env, reason]
            } else if (promotionTargetServerRootUrls) {
                // If the AMI is not in use on master server, check promoted data.
                for (String remoteServer in promotionTargetServerRootUrls) {
                    String url = "${remoteServer}/${userContext.region}/image/references/${value}"
                    JSONElement json = command.restClientService.getAsJson(url)
                    if (json == null) {
                        return ['image.imageId.remoteInaccessible', value, url]
                    }
                    Collection<String> remoteInstances = json.instances
                    Collection<String> remoteLaunchConfigurations = json.launchConfigurations
                    if (remoteInstances || remoteLaunchConfigurations) {
                        String reason = constructReason(remoteInstances, remoteLaunchConfigurations)
                        return ['image.imageId.used', value, remoteServer, reason]
                    }
                }
            }
            null
        })
    }

    static constructReason(Collection<String> instanceIds, Collection<String> launchConfigurationNames) {
        instanceIds ? "instance${instanceIds.size() == 1 ? '' : 's'} $instanceIds" :
            "launch configuration${launchConfigurationNames.size() == 1 ? '' : 's'} $launchConfigurationNames"
    }
}
	
	