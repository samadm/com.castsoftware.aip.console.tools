package com.castsoftware.aip.console.tools.core.services;

import com.castsoftware.aip.console.tools.core.dto.ApplicationDto;
import com.castsoftware.aip.console.tools.core.dto.Applications;
import com.castsoftware.aip.console.tools.core.dto.BaseDto;
import com.castsoftware.aip.console.tools.core.dto.DeliveryConfigurationDto;
import com.castsoftware.aip.console.tools.core.dto.NodeDto;
import com.castsoftware.aip.console.tools.core.dto.VersionDto;
import com.castsoftware.aip.console.tools.core.dto.jobs.JobState;
import com.castsoftware.aip.console.tools.core.exceptions.ApiCallException;
import com.castsoftware.aip.console.tools.core.exceptions.ApplicationServiceException;
import com.castsoftware.aip.console.tools.core.exceptions.JobServiceException;
import com.castsoftware.aip.console.tools.core.utils.ApiEndpointHelper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

import static java.util.stream.Collectors.toSet;

@Log
public class ApplicationServiceImpl implements ApplicationService {

    private RestApiService restApiService;
    private JobsService jobService;

    public ApplicationServiceImpl(RestApiService restApiService, JobsService jobsService) {
        this.restApiService = restApiService;
        this.jobService = jobsService;
    }

    @Override
    public String getApplicationGuidFromName(String applicationName) throws ApplicationServiceException {
        return getApplications()
                .getApplications()
                .stream()
                .filter(Objects::nonNull)
                .filter(a -> StringUtils.equalsAnyIgnoreCase(applicationName, a.getName()))
                .findFirst()
                .map(ApplicationDto::getGuid)
                .orElse(null);
    }

    @Override
    public String getApplicationNameFromGuid(String applicationGuid) throws ApplicationServiceException {
        return getApplications()
                .getApplications()
                .stream()
                .filter(Objects::nonNull)
                .filter(a -> StringUtils.equalsAnyIgnoreCase(applicationGuid, a.getGuid()))
                .findFirst()
                .map(ApplicationDto::getName)
                .orElse(null);
    }

    @Override
    public boolean applicationHasVersion(String applicationGuid) throws ApplicationServiceException {
        Set<VersionDto> appVersions = getApplicationVersion(applicationGuid);
        return appVersions != null &&
                !appVersions.isEmpty();
    }

    @Override
    public String getOrCreateApplicationFromName(String applicationName, boolean autoCreate) throws ApplicationServiceException {
        return getOrCreateApplicationFromName(applicationName, autoCreate, null);
    }

    @Override
    public String getOrCreateApplicationFromName(String applicationName, boolean autoCreate, String nodeName) throws ApplicationServiceException {
        if (StringUtils.isBlank(applicationName)) {
            throw new ApplicationServiceException("No application name provided.");
        }

        Optional<ApplicationDto> appDto = getApplications()
                .getApplications()
                .stream()
                .filter(Objects::nonNull)
                .filter(a -> StringUtils.equalsAnyIgnoreCase(applicationName, a.getName()))
                .findFirst();

        if (!appDto.isPresent()) {
            if (!autoCreate) {
                return null;
            }
            try {
                String nodeGuid = null;
                if (StringUtils.isNotBlank(nodeName)) {
                    nodeGuid = restApiService.getForEntity("/api/nodes", new TypeReference<List<NodeDto>>() {
                    }).stream()
                            .filter(n -> StringUtils.equalsIgnoreCase(nodeName, n.getName()))
                            .map(NodeDto::getGuid)
                            .findFirst()
                            .orElse(null);
                    if (nodeGuid == null) {
                        throw new ApplicationServiceException("Node with name " + nodeName + " could not be found on AIP Console to create the new application");
                    }
                }
                String infoMessage = String.format("Application '%s' not found and 'auto create' enabled. Starting application creation", applicationName);
                if (nodeGuid != null) {
                    infoMessage += " on node " + nodeName;
                }
                log.info(infoMessage);

                String jobGuid = jobService.startCreateApplication(applicationName, nodeGuid);
                return jobService.pollAndWaitForJobFinished(jobGuid, (s) -> s.getState() == JobState.COMPLETED ? s.getAppGuid() : null);
            } catch (JobServiceException | ApiCallException e) {
                log.log(Level.SEVERE, "Could not create the application due to the following error", e);
                throw new ApplicationServiceException("Unable to create application automatically.", e);
            }
        }
        return appDto.get().getGuid();
    }

    private Applications getApplications() throws ApplicationServiceException {
        try {
            Applications result = restApiService.getForEntity(ApiEndpointHelper.getApplicationsPath(), Applications.class);
            return result == null ? new Applications() : result;
        } catch (ApiCallException e) {
            throw new ApplicationServiceException("Unable to get applications from AIP Console", e);
        }
    }

    @Override
    public Set<VersionDto> getApplicationVersion(String appGuid) throws ApplicationServiceException {
        try {
            return restApiService.getForEntity(ApiEndpointHelper.getApplicationVersionsPath(appGuid), new TypeReference<Set<VersionDto>>() {
            });
        } catch (ApiCallException e) {
            throw new ApplicationServiceException("Unable to retrieve the applications' versions", e);
        }
    }

    @Override
    public String createDeliveryConfiguration(String appGuid, String exclusionPatterns) throws ApiCallException {
        Set<String> patterns = Arrays.stream(exclusionPatterns.split(",")).collect(toSet());
        DeliveryConfigurationDto deliveryConfigurationDto = DeliveryConfigurationDto.builder()
                .ignorePatterns(patterns)
                .packages(new HashSet())
                .build();

        BaseDto response = restApiService.postForEntity("/api/applications/" + appGuid + "/delivery-configuration", deliveryConfigurationDto, BaseDto.class);
        log.fine("Delivery configuration response " + response);
        return response != null ? response.getGuid() : null;
    }
}
