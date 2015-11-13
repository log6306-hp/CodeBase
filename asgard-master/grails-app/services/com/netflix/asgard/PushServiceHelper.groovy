package com.netflix.asgard;

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.ec2.model.Image
import com.amazonaws.services.ec2.model.SecurityGroup
import com.netflix.asgard.model.InstancePriceType
import com.netflix.asgard.model.Subnets
import com.netflix.asgard.model.ZoneAvailability
import com.netflix.asgard.push.GroupActivateOperation
import com.netflix.asgard.push.GroupCreateOperation
import com.netflix.asgard.push.GroupCreateOptions
import com.netflix.asgard.push.GroupDeactivateOperation
import com.netflix.asgard.push.GroupDeleteOperation
import com.netflix.asgard.push.GroupResizeOperation
import com.netflix.asgard.push.InitialTraffic
import com.netflix.asgard.push.RollingPushOperation
import com.netflix.asgard.push.RollingPushOptions
import java.rmi.NoSuchObjectException

class PushServiceHelper {
	
	def awsEc2Service
	def userContext
	def instanceType
	
	AutoScalingGroup group
	
	PushServiceHelper(def awsEc2Service_, UserContext userContext_, def instanceType_, AutoScalingGroup group_){
		this.awsEc2Service = awsEc2Service_
		this.userContext = userContext_
		this.instanceType = instanceType_
		this.group = group_
	}
	
	def getMapForEditPreparation(LaunchConfiguration lc, def instanceType, def showAllImages){
		
		List<ZoneAvailability> zoneAvailabilities = awsEc2Service.getZoneAvailabilities(userContext, instanceType)
		Collection<Image> images = awsEc2Service.getAccountImages(userContext)
		Integer fullCount = images.size()
		Image currentImage = awsEc2Service.getImage(userContext, lc.imageId)
		if (currentImage && !showAllImages) {
			images = awsEc2Service.getImagesForPackage(userContext, currentImage.packageName)
		}
		Boolean imageListIsShort = images.size() < fullCount
		Subnets subnets = awsEc2Service.getSubnets(userContext)
		List<SecurityGroup> effectiveSecurityGroups = awsEc2Service.getEffectiveSecurityGroups(userContext)
		String vpcId = subnets.getVpcIdForVpcZoneIdentifier(group.VPCZoneIdentifier)
		Map<String, String> purposeToVpcId = subnets.mapPurposeToVpcId()
		
		Map<String, Object> map = [
			zoneAvailabilities: zoneAvailabilities,
			images: images.sort { it.imageLocation.toLowerCase() },
			imageListIsShort: imageListIsShort,
			securityGroupsGroupedByVpcId: effectiveSecurityGroups.groupBy { it.vpcId },
			vpcId: vpcId,
			purposeToVpcId: purposeToVpcId,
			]
	}
}