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

@ContextParam('region')
class ImageController {

    def awsEc2Service
    def awsAutoScalingService
    def imageService
    def instanceTypeService
    def launchTemplateService
    def mergedInstanceGroupingService
    def taskService
    def grailsApplication

    static allowedMethods = [update: 'POST', delete: ['POST', 'DELETE'], launch: 'POST', addTag: 'POST',
            addTags: 'POST', removeTag: ['POST', 'DELETE'], removeTags: ['POST', 'DELETE'], removeAllTags: 'DELETE',
            massDelete: ['POST', 'DELETE']]

    static editActions = ['prelaunch']

    def index() {
        redirect(action: 'list', params: params)
    }

    def list() {
        UserContext userContext = UserContext.of(request)
        Collection<Image> images = []
        Set<String> packageNames = Requests.ensureList(params.id).collect { it.split(',') }.flatten() as Set<String>
        if (packageNames) {
            images = packageNames.collect { awsEc2Service.getImagesForPackage(userContext, it) }.flatten()
        } else {
            images = awsEc2Service.getImagesForPackage(userContext, '')
        }
        images = images.sort { it.imageLocation.toLowerCase() }
        Map<String, String> accounts = grailsApplication.config.grails.awsAccountNames
        withFormat {
            html { [images: images, packageNames: packageNames, accounts: accounts] }
            xml { new XML(images).render(response) }
            json { new JSON(images).render(response) }
        }
    }

    def show() {
        UserContext userContext = UserContext.of(request)
        String imageId = EntityType.image.ensurePrefix(params.imageId ?: params.id)
        Image image = imageId ? awsEc2Service.getImage(userContext, imageId) : null
        image?.tags?.sort { it.key }
        if (!image) {
            Requests.renderNotFound('Image', imageId, this)
        } else {
            List<String> launchUsers = []
            try { launchUsers = awsEc2Service.getImageLaunchers(userContext, image.imageId) }
            catch (AmazonServiceException ignored) { /* We may not own the image, so ignore failures here */ }
            String snapshotId = image.blockDeviceMappings.findResult { it.ebs?.snapshotId }
            String ownerId = image.ownerId
            Map<String, String> accounts = grailsApplication.config.grails.awsAccountNames
            Map details = [
                    image: image,
                    snapshotId: snapshotId,
                    launchUsers: launchUsers,
                    accounts: accounts,
                    accountName: accounts[ownerId] ?: ownerId
            ]
            withFormat {
                html { return details }
                xml { new XML(details).render(response) }
                json { new JSON(details).render(response) }
            }
        }
    }

    def edit() {
        UserContext userContext = UserContext.of(request)
        def launchUsers = []
        def imageId = EntityType.image.ensurePrefix(params.imageId ?: params.id)
        try {
            launchUsers = awsEc2Service.getImageLaunchers(userContext, imageId)
        }
        catch (Exception e) {
            flash.message = "Unable to modify ${imageId} on this account because ${e}"
            redirect(action: 'show', params: params)
        }
        [
                image: awsEc2Service.getImage(userContext, imageId),
                launchPermissions: launchUsers,
                accounts: grailsApplication.config.grails.awsAccountNames
        ]
    }

    def update() {
        def imageId = EntityType.image.ensurePrefix(params.imageId)
        UserContext userContext = UserContext.of(request)
        List<String> launchPermissions = Requests.ensureList(params.launchPermissions)
        try {
            awsEc2Service.setImageLaunchers(userContext, imageId, launchPermissions)
            flash.message = "Image '${imageId}' has been updated."
        } catch (Exception e) {
            flash.message = "Could not update Image: ${e}"
        }
        redirect(action: 'show', params: [id: imageId])
    }

    def delete(ImageDeleteCommand cmd) {
        if (cmd.hasErrors()) {
            chain(action: 'show', model: [cmd: cmd], params: params) // Use chain to pass both the errors and the params
        } else {
            UserContext userContext = UserContext.of(request)
            String imageId = params.id
            try {
                Image image = awsEc2Service.getImage(userContext, imageId, From.CACHE)
                String packageName = image.getPackageName()
                imageService.deleteImage(userContext, imageId)
                flash.message = "Image '${imageId}' has been deleted."
                redirect(action: 'list', params: [id: packageName])
            } catch (Exception e) {
                flash.message = "Could not delete image: ${e}"
                redirect(action: 'show', params: [id: imageId])
            }
        }
    }

    def prelaunch() {
        UserContext userContext = UserContext.of(request)
        String imageId = EntityType.image.ensurePrefix(params.id)
        Collection<InstanceTypeData> instanceTypes = instanceTypeService.getInstanceTypes(userContext)
        [
                 'imageId' : imageId,
                 'instanceType' : '',
                 'instanceTypes' : instanceTypes,
                 'securityGroups' : awsEc2Service.getEffectiveSecurityGroups(userContext),
                 'zone' : 'any',
                 'zoneList' : awsEc2Service.getRecommendedAvailabilityZones(userContext)
        ]
    }

    def launch() {
        ImageControllerHelper imgH = new ImageControllerHelper()
		imgH.getLaunchFormat(imageService, launchTemplateService)
    }

    def result() {
        render view: '/common/result'
    }

    def references() {
        UserContext userContext = UserContext.of(request)
        String imageId = EntityType.image.ensurePrefix(params.imageId ?: params.id)
        Collection<Instance> instances = awsEc2Service.getInstancesUsingImageId(userContext, imageId)
        Collection<LaunchConfiguration> launchConfigurations =
                awsAutoScalingService.getLaunchConfigurationsUsingImageId(userContext, imageId)
        Map result = [:]
        result['instances'] = instances.collect { it.instanceId }
        result['launchConfigurations'] = launchConfigurations.collect { it.launchConfigurationName }
        render result as JSON
    }

    def used() {
        UserContext userContext = UserContext.of(request)
        Collection<String> imageIdsInUse = imageService.getLocalImageIdsInUse(userContext)
        withFormat {
            html { render imageIdsInUse.toString() }
            xml { new XML(imageIdsInUse).render(response) }
            json { new JSON(imageIdsInUse).render(response) }
        }
    }

    /**
     * Adds or replaces a tag on the image. Expects the following params:
     *         imageId (in the POST data) or id (on URL) - the id of the image to tag
     *         name - the key of the tag to add or replace
     *         value - the value of the tag to add or replace
     */
    def addTag() {
        String imageId = params.imageId ?: params.id
        Check.notEmpty(imageId, 'imageId')
        performAddTags([imageId])
    }

    /**
     * Adds or replaces a tag on a set of images in batch. Expects the following params:
     *         imageIds - comma separated list of image ids to add or replace tags on
     *         name - the key of the tag to add or replace
     *         value - the value of the tag to add or replace
     */
    def addTags() {
        performAddTags(params.imageIds?.tokenize(','))
    }

    private performAddTags(Collection<String> imageIds) {
        String name = params.name
        String value = params.value
        Check.notEmpty(name, 'name')
        Check.notEmpty(value, "value for ${name}")
        Check.notEmpty(imageIds, 'imageIds')
        Collection<String> prefixedImageIds = imageIds.collect { EntityType.image.ensurePrefix(it) }
        UserContext userContext = UserContext.of(request)
        awsEc2Service.createImageTags(userContext, prefixedImageIds, name, value)
        render "Tag ${name}=${value} added to image${imageIds.size() > 1 ? 's' : ''} ${prefixedImageIds.join(', ')}"
    }

    /**
     * Removes a tag from an image based on the key of the tag. Expects the following params:
     *         imageId (in the POST data) or id (on URL) - the id of the image to remove the tag on
     *         name - the key of the tag to remove on the image
     */
    def removeTag() {
        String imageId = params.imageId ?: params.id
        Check.notEmpty(imageId, 'imageId')
        performRemoveTags([imageId])
    }

    /**
     * Removes a tag from an image based on the key of the tag. Expects the following params:
     *         imageIds - comma separated list of image ids to remove tags on
     *         name - the key of the tag to remove on the image
     */
    def removeTags() {
        performRemoveTags(params.imageIds?.tokenize(','))
    }

    private void performRemoveTags(Collection<String> imageIds) {
        String name = params.name
        Check.notEmpty(name, 'name')
        Check.notEmpty(imageIds, 'imageIds')
        Collection<String> prefixedImageIds = imageIds.collect { EntityType.image.ensurePrefix(it) }
        UserContext userContext = UserContext.of(request)
        awsEc2Service.deleteImageTags(userContext, prefixedImageIds, name)
        render "Tag ${name} removed from image${imageIds.size() > 1 ? 's' : ''} ${imageIds.join(', ')}"
    }

    def replicateTags() {
        log.info 'image/replicateTags called. Starting unscheduled image tag replication.'
        imageService.replicateImageTags()
        render 'done'
    }

    def massDelete() {
        UserContext userContext = UserContext.of(request)
        MassDeleteRequest massDeleteRequest = new MassDeleteRequest()
        DataBindingUtils.bindObjectToInstance(massDeleteRequest, params)
        List<Image> deleted = imageService.massDelete(userContext, massDeleteRequest)

        Integer count = deleted.size()
        String executeMessage = "Started deleting the following ${count} images in ${userContext.region}:\n"
        Region region = userContext.region
        String dryRunMessage = "Dry run mode. If executed, this job would delete ${count} images in ${region}:\n"
        String initialMessage = JanitorMode.EXECUTE == massDeleteRequest.mode ? executeMessage : dryRunMessage
        String message = deleted.inject(initialMessage) { message, image -> message + image + '\n' }
        render "<pre>\n${message}</pre>\n"
    }

	
    def analyze() {
		
		ImageControllerHelper imgHlpr = new ImageControllerHelper(awsEc2Service_, mergedInstanceGroupingService_)
		
		def details = imgHlpr.getDetailMap()
		
        withFormat {
            html { details }
            xml { new XML(details).render(response) }
            json { new JSON(details).render(response) }
        }
    }

    def tagAmiLastReferencedTime() {
        UserContext userContext = UserContext.of(request)
        String imageTagMasterAccount = grailsApplication.config.cloud.imageTagMasterAccount
        if (grailsApplication.config.cloud.accountName == imageTagMasterAccount) {
            imageService.tagAmiLastReferencedTime(userContext)
            render 'Image last_referenced_time tagging started'
        } else {
            render "This operation can only be run in the ${imageTagMasterAccount} account which controls image tags"
        }
    }
}


