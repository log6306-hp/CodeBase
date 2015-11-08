package com.netflix.asgard;

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.ec2.model.Image
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.SpotInstanceRequest
import com.netflix.asgard.model.InstanceTypeData
import com.netflix.asgard.model.JanitorMode
import com.netflix.asgard.model.MassDeleteRequest
import com.netflix.grails.contextParam.ContextParam
import grails.converters.JSON
import grails.converters.XML
import org.codehaus.groovy.grails.web.binding.DataBindingUtils
import org.codehaus.groovy.grails.web.json.JSONElement

class ImageControllerHelper {

	def awsEc2Service
	def mergedInstanceGroupingService
	
	public ImageControllerHelper(def awsEc2Service_, def mergedInstanceGroupingService_){
		awsEc2Service = awsEc2Service_
		mergedInstanceGroupingService = mergedInstanceGroupingService_
	}
	
	public ImageControllerHelper(){
	}
	
	def getDetailMap(){
		
		UserContext userContext = UserContext.of(request)
		Collection<Image> allImages = awsEc2Service.getAccountImages(userContext)
		List<Image> dateless = []
		List<Image> baseless = []
		List<Image> baselessInUse = []

		Set<String> amisInUse = [] as Set
		Map<String, List<MergedInstance>> imageIdsToInstanceLists = [:]
		List<MergedInstance> instances = mergedInstanceGroupingService.getMergedInstances(userContext, '')
		instances.each { MergedInstance instance ->
			if (instance.amiId) {
				String amiId = instance.amiId
				amisInUse << amiId

				List<MergedInstance> instancesForAmi = imageIdsToInstanceLists.get(amiId)
				if (instancesForAmi) {
					instancesForAmi.add(instance)
				} else {
					imageIdsToInstanceLists.put(amiId, [instance])
				}
			}
		}

		allImages.each { Image image ->
			// Look through all images and read descriptions looking for base AMIs and ancestors.
			if (!image.baseAmiId) { baseless << image }
			if (!image.creationTime) { dateless << image }
		}

		Map<String> deregisteredAmisToInstanceAsgs = [:]

		List<Image> inUseImages = []
		imageIdsToInstanceLists.keySet().each { String inUseAmiId ->
			Image inUseImage = allImages.find { Image im -> im.imageId == inUseAmiId }
			if (inUseImage) {
				inUseImages << inUseImage
			} else {
				deregisteredAmisToInstanceAsgs.put(inUseAmiId,
					imageIdsToInstanceLists[inUseAmiId].collect {
						MergedInstance inst-> new Expando('instance': inst, 'groupName': inst.autoScalingGroupName)
					}
				)
			}
		}
		inUseImages = inUseImages.sort { it.baseAmiDate }

		Map<String, List<Image>> appVersionsToImageLists = [:]
		inUseImages.each { Image image ->
			if (image.appVersion) {
				List<Image> imageList = appVersionsToImageLists[image.appVersion] ?: []
				imageList << image
				appVersionsToImageLists[image.appVersion] = imageList
			}
		}
		appVersionsToImageLists = appVersionsToImageLists.sort().sort { a, b -> b.value.size() <=> a.value.size() }

		baseless.each { Image image ->
			if (imageIdsToInstanceLists.keySet().contains(image.imageId)) {
				baselessInUse << image
			}
		}

		Map details = [
				'dateless': dateless,
				'baseless': baseless,
				'baselessInUse': baselessInUse,
				'inUseImages': inUseImages,
				'appVersionsToImageLists': appVersionsToImageLists,
				'imageIdsToInstanceLists': imageIdsToInstanceLists,
				'deregisteredAmisToInstanceAsgs': deregisteredAmisToInstanceAsgs
		]
		return details;
	}

	def getLaunchFormat(def imageService, def launchTemplateService){
		
		String message = ''
		Closure output = { }
		List<String> instanceIds = []
		List<String> spotInstanceRequestIds = []

		try {
			UserContext userContext = UserContext.of(request)
			String pricing = params.pricing
			String pricingMissingMessage = 'Missing required parameter pricing=spot or pricing=ondemand'
			Check.condition(pricingMissingMessage, { pricing in ['ondemand', 'spot'] })
			String imageId = EntityType.image.ensurePrefix(params.imageId)
			String owner = Check.notEmpty(params.owner as String, 'owner')
			String zone = params.zone
			String instanceType = params.instanceType
			List<String> rawSecurityGroups = Requests.ensureList(params.selectedGroups)
			Collection<String> securityGroups = launchTemplateService.
				includeDefaultSecurityGroupsForNonVpc(rawSecurityGroups)
			Integer count = 1
			if (pricing == 'ondemand') {
				List<Instance> launchedInstances = imageService.runOnDemandInstances(userContext, imageId, count,
						securityGroups, instanceType, zone, owner)
				instanceIds = launchedInstances*.instanceId
				message = "Image '${imageId}' has been launched as ${instanceIds}"
				output = { instances { instanceIds.each { instance(it) } } }
			} else {
				List<SpotInstanceRequest> sirs = imageService.requestSpotInstances(userContext, imageId, count,
						securityGroups, instanceType, zone, owner)
				spotInstanceRequestIds = sirs*.spotInstanceRequestId
				message = "Image '${imageId}' has been used to create Spot Instance Request ${spotInstanceRequestIds}"
				output = { spotInstanceRequests { spotInstanceRequestIds.each { spotInstanceRequest(it) } } }
			}
		} catch (Exception e) {
			message = "Could not launch Image: ${e}"
			output = { error(message) }
		}
		
		return withFormat {
			form {
				flash.message = message
				Map redirectParams = [action: 'result']
				if (instanceIds) {
					redirectParams = [controller: 'instance', action: 'show', id: instanceIds[0]]
				} else if (spotInstanceRequestIds) {
					redirectParams = [controller: 'spotInstanceRequest', action: 'show', id: spotInstanceRequestIds[0]]
				}
				redirect(redirectParams)
			}
			xml { render(contentType: 'application/xml', output) }
			json { render(contentType: 'application/json', output) }
		}
		
	}
				
}