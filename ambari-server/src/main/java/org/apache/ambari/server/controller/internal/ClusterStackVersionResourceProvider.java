/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.controller.internal;

import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.JDK_LOCATION;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.annotations.Experimental;
import org.apache.ambari.annotations.ExperimentalFeature;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.ActionExecutionContext;
import org.apache.ambari.server.controller.AmbariActionExecutionHelper;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.RequestStatusResponse;
import org.apache.ambari.server.controller.spi.NoSuchParentResourceException;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.Resource.Type;
import org.apache.ambari.server.controller.spi.ResourceAlreadyExistsException;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.UnsupportedPropertyException;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.orm.dao.HostVersionDAO;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.entities.HostVersionEntity;
import org.apache.ambari.server.orm.entities.OperatingSystemEntity;
import org.apache.ambari.server.orm.entities.RepositoryEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.StackEntity;
import org.apache.ambari.server.orm.entities.UpgradeEntity;
import org.apache.ambari.server.security.authorization.RoleAuthorization;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ComponentInfo;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.RepositoryType;
import org.apache.ambari.server.state.RepositoryVersionState;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.repository.VersionDefinitionXml;
import org.apache.ambari.server.state.stack.upgrade.RepositoryVersionHelper;
import org.apache.ambari.server.utils.StageUtils;
import org.apache.ambari.server.utils.VersionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.persist.Transactional;

/**
 * Resource provider for cluster stack versions resources.
 */
@StaticallyInject
public class ClusterStackVersionResourceProvider extends AbstractControllerResourceProvider {

  // ----- Property ID constants ---------------------------------------------

  protected static final String CLUSTER_STACK_VERSION_ID_PROPERTY_ID = PropertyHelper.getPropertyId("ClusterStackVersions", "id");
  protected static final String CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("ClusterStackVersions", "cluster_name");
  protected static final String CLUSTER_STACK_VERSION_STACK_PROPERTY_ID = PropertyHelper.getPropertyId("ClusterStackVersions", "stack");
  protected static final String CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID = PropertyHelper.getPropertyId("ClusterStackVersions", "version");
  protected static final String CLUSTER_STACK_VERSION_STATE_PROPERTY_ID = PropertyHelper.getPropertyId("ClusterStackVersions", "state");
  protected static final String CLUSTER_STACK_VERSION_HOST_STATES_PROPERTY_ID = PropertyHelper.getPropertyId("ClusterStackVersions", "host_states");
  protected static final String CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID  = PropertyHelper.getPropertyId("ClusterStackVersions", "repository_version");
  protected static final String CLUSTER_STACK_VERSION_STAGE_SUCCESS_FACTOR  = PropertyHelper.getPropertyId("ClusterStackVersions", "success_factor");

  /**
   * Forces the {@link HostVersionEntity}s to a specific
   * {@link RepositoryVersionState}. When used during the creation of
   * {@link HostVersionEntity}s, this will set the state to
   * {@link RepositoryVersionState#INSTALLED}. When used during the update of a
   * cluster stack version, this will force all entities to
   * {@link RepositoryVersionState#CURRENT}.
   *
   */
  protected static final String CLUSTER_STACK_VERSION_FORCE = "ClusterStackVersions/force";

  protected static final String INSTALL_PACKAGES_ACTION = "install_packages";
  protected static final String INSTALL_PACKAGES_FULL_NAME = "Install Version";

  /**
   * The default success factor that will be used when determining if a stage's
   * failure should cause other stages to abort. Consider a scenario with 1000
   * hosts, broken up into 10 stages. Each stage would have 100 hosts. If the
   * success factor was 100%, then any failure in stage 1 woudl cause all 9
   * other stages to abort. If set to 90%, then 10 hosts would need to fail for
   * the other stages to abort. This is necessary to prevent the abortion of
   * stages based on 1 or 2 errant hosts failing in a large cluster's stack
   * distribution.
   */
  private static final float INSTALL_PACKAGES_SUCCESS_FACTOR = 0.85f;

  private static Set<String> pkPropertyIds = Sets.newHashSet(
      CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID, CLUSTER_STACK_VERSION_ID_PROPERTY_ID,
      CLUSTER_STACK_VERSION_STACK_PROPERTY_ID, CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID,
      CLUSTER_STACK_VERSION_STATE_PROPERTY_ID,
      CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID);

  private static Set<String> propertyIds = Sets.newHashSet(CLUSTER_STACK_VERSION_ID_PROPERTY_ID,
      CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID, CLUSTER_STACK_VERSION_STACK_PROPERTY_ID,
      CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID, CLUSTER_STACK_VERSION_HOST_STATES_PROPERTY_ID,
      CLUSTER_STACK_VERSION_STATE_PROPERTY_ID, CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID,
      CLUSTER_STACK_VERSION_STAGE_SUCCESS_FACTOR, CLUSTER_STACK_VERSION_FORCE);

  private static Map<Type, String> keyPropertyIds = ImmutableMap.<Type, String> builder()
      .put(Type.Cluster, CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID)
      .put(Type.ClusterStackVersion, CLUSTER_STACK_VERSION_ID_PROPERTY_ID)
      .put(Type.Stack, CLUSTER_STACK_VERSION_STACK_PROPERTY_ID)
      .put(Type.StackVersion, CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID)
      .put(Type.RepositoryVersion, CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID)
      .build();

  @Inject
  private static HostVersionDAO hostVersionDAO;

  @Inject
  private static RepositoryVersionDAO repositoryVersionDAO;

  @Inject
  private static Provider<AmbariActionExecutionHelper> actionExecutionHelper;

  @Inject
  private static StageFactory stageFactory;

  @Inject
  private static RequestFactory requestFactory;

  @Inject
  private static Configuration configuration;

  @Inject
  private static RepositoryVersionHelper repoVersionHelper;



  @Inject
  private static Provider<Clusters> clusters;

  /**
   * Constructor.
   */
  public ClusterStackVersionResourceProvider(
          AmbariManagementController managementController) {
    super(propertyIds, keyPropertyIds, managementController);

    setRequiredCreateAuthorizations(EnumSet.of(RoleAuthorization.AMBARI_MANAGE_STACK_VERSIONS, RoleAuthorization.CLUSTER_UPGRADE_DOWNGRADE_STACK));
    setRequiredDeleteAuthorizations(EnumSet.of(RoleAuthorization.AMBARI_MANAGE_STACK_VERSIONS, RoleAuthorization.CLUSTER_UPGRADE_DOWNGRADE_STACK));
    setRequiredUpdateAuthorizations(EnumSet.of(RoleAuthorization.AMBARI_MANAGE_STACK_VERSIONS, RoleAuthorization.CLUSTER_UPGRADE_DOWNGRADE_STACK));
  }

  @Override
  @Experimental(feature = ExperimentalFeature.PATCH_UPGRADES,
    comment = "this is a fake response until the UI no longer uses the endpoint")
  public Set<Resource> getResourcesAuthorized(Request request, Predicate predicate) throws
      SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
    final Set<Resource> resources = new HashSet<>();

    final Set<String> requestedIds = getRequestPropertyIds(request, predicate);
    final Set<Map<String, Object>> propertyMaps = getPropertyMaps(predicate);

    if (1 != propertyMaps.size()) {
      throw new SystemException("Cannot request more than one resource");
    }

    Map<String, Object> propertyMap = propertyMaps.iterator().next();

    String clusterName = propertyMap.get(CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID).toString();
    final Cluster cluster;
    try {
      cluster = clusters.get().getCluster(clusterName);
    } catch (AmbariException e) {
      throw new SystemException(e.getMessage(), e);
    }

    Set<Long> requestedEntities = new HashSet<>();

    if (propertyMap.containsKey(CLUSTER_STACK_VERSION_ID_PROPERTY_ID)) {
      Long id = Long.parseLong(propertyMap.get(CLUSTER_STACK_VERSION_ID_PROPERTY_ID).toString());
      requestedEntities.add(id);
    } else {
      List<RepositoryVersionEntity> entities = repositoryVersionDAO.findAll();

      for (RepositoryVersionEntity entity : entities) {
        requestedEntities.add(entity.getId());
      }
    }

    if (requestedEntities.isEmpty()) {
      throw new SystemException("Could not find any repositories to show");
    }


    for (Long repositoryVersionId : requestedEntities) {
      final Resource resource = new ResourceImpl(Resource.Type.ClusterStackVersion);

      RepositoryVersionEntity repositoryVersion = repositoryVersionDAO.findByPK(repositoryVersionId);

      final List<RepositoryVersionState> allStates = new ArrayList<>();
      final Map<RepositoryVersionState, List<String>> hostStates = new HashMap<>();
      for (RepositoryVersionState state: RepositoryVersionState.values()) {
        hostStates.put(state, new ArrayList<String>());
      }

      StackEntity repoVersionStackEntity = repositoryVersion.getStack();
      StackId repoVersionStackId = new StackId(repoVersionStackEntity);

      List<HostVersionEntity> hostVersionsForRepository = hostVersionDAO.findHostVersionByClusterAndRepository(
          cluster.getClusterId(), repositoryVersion);

      // create the in-memory structures
      for (HostVersionEntity hostVersionEntity : hostVersionsForRepository) {
        hostStates.get(hostVersionEntity.getState()).add(hostVersionEntity.getHostName());
        allStates.add(hostVersionEntity.getState());
      }

      setResourceProperty(resource, CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID, clusterName, requestedIds);
      setResourceProperty(resource, CLUSTER_STACK_VERSION_HOST_STATES_PROPERTY_ID, hostStates, requestedIds);
      setResourceProperty(resource, CLUSTER_STACK_VERSION_ID_PROPERTY_ID, repositoryVersion.getId(), requestedIds);
      setResourceProperty(resource, CLUSTER_STACK_VERSION_STACK_PROPERTY_ID, repoVersionStackId.getStackName(), requestedIds);
      setResourceProperty(resource, CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID, repoVersionStackId.getStackVersion(), requestedIds);
      setResourceProperty(resource, CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID, repositoryVersion.getId(), requestedIds);

      @Experimental(feature = ExperimentalFeature.PATCH_UPGRADES,
          comment = "this is a fake status until the UI can handle services that are on their own")
      RepositoryVersionState aggregateState = RepositoryVersionState.getAggregateState(allStates);
      setResourceProperty(resource, CLUSTER_STACK_VERSION_STATE_PROPERTY_ID, aggregateState, requestedIds);

      if (predicate == null || predicate.evaluate(resource)) {
        resources.add(resource);
      }
    }

    return resources;
  }


  @Override
  public RequestStatus createResourcesAuthorized(Request request) throws SystemException,
          UnsupportedPropertyException, ResourceAlreadyExistsException,
          NoSuchParentResourceException {

    if (request.getProperties().size() > 1) {
      throw new UnsupportedOperationException("Multiple requests cannot be executed at the same time.");
    }

    Iterator<Map<String, Object>> iterator = request.getProperties().iterator();

    String clName;
    final String desiredRepoVersion;
    String stackName;
    String stackVersion;

    Map<String, Object> propertyMap = iterator.next();

    Set<String> requiredProperties = new HashSet<>();
    requiredProperties.add(CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID);
    requiredProperties.add(CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID);
    requiredProperties.add(CLUSTER_STACK_VERSION_STACK_PROPERTY_ID);
    requiredProperties.add(CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID);

    for (String requiredProperty : requiredProperties) {
      if (! propertyMap.containsKey(requiredProperty)) {
        throw new IllegalArgumentException(
                String.format("The required property %s is not defined",
                        requiredProperty));
      }
    }

    clName = (String) propertyMap.get(CLUSTER_STACK_VERSION_CLUSTER_NAME_PROPERTY_ID);
    desiredRepoVersion = (String) propertyMap.get(CLUSTER_STACK_VERSION_REPOSITORY_VERSION_PROPERTY_ID);

    Cluster cluster;
    AmbariManagementController managementController = getManagementController();
    AmbariMetaInfo ami = managementController.getAmbariMetaInfo();

    try {
      Clusters clusters = managementController.getClusters();
      cluster = clusters.getCluster(clName);
    } catch (AmbariException e) {
      throw new NoSuchParentResourceException(e.getMessage(), e);
    }

    UpgradeEntity entity = cluster.getUpgradeInProgress();
    if (null != entity) {
      throw new IllegalArgumentException(String.format(
          "Cluster %s %s is in progress.  Cannot install packages.",
          cluster.getClusterName(), entity.getDirection().getText(false)));
    }

    Set<StackId> stackIds = new HashSet<>();
    if (propertyMap.containsKey(CLUSTER_STACK_VERSION_STACK_PROPERTY_ID) &&
            propertyMap.containsKey(CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID)) {
      stackName = (String) propertyMap.get(CLUSTER_STACK_VERSION_STACK_PROPERTY_ID);
      stackVersion = (String) propertyMap.get(CLUSTER_STACK_VERSION_VERSION_PROPERTY_ID);
      StackId stackId = new StackId(stackName, stackVersion);
      if (! ami.isSupportedStack(stackName, stackVersion)) {
        throw new NoSuchParentResourceException(String.format("Stack %s is not supported",
                stackId));
      }
      stackIds.add(stackId);
    } else { // Using stack that is current for cluster
      for (Service service : cluster.getServices().values()) {
        stackIds.add(service.getDesiredStackId());
      }
    }

    if (stackIds.size() > 1) {
      throw new SystemException("Could not determine stack to add out of " + StringUtils.join(stackIds, ','));
    }

    StackId stackId = stackIds.iterator().next();
    stackName = stackId.getStackName();
    stackVersion = stackId.getStackVersion();

    RepositoryVersionEntity repoVersionEntity = repositoryVersionDAO.findByStackAndVersion(
        stackId, desiredRepoVersion);

    if (repoVersionEntity == null) {
      throw new IllegalArgumentException(String.format(
              "Repo version %s is not available for stack %s",
              desiredRepoVersion, stackId));
    }

    VersionDefinitionXml desiredVersionDefinition = null;
    try {
      desiredVersionDefinition = repoVersionEntity.getRepositoryXml();
    } catch (Exception e) {
      throw new IllegalArgumentException(
          String.format("Version %s is backed by a version definition, but it could not be parsed", desiredRepoVersion), e);
    }

    // if true, then we need to force all new host versions into the INSTALLED state
    boolean forceInstalled = Boolean.parseBoolean((String)propertyMap.get(
        CLUSTER_STACK_VERSION_FORCE));

    try {
      // either create the necessary host version entries, or set them to INSTALLING when attempting to re-distribute an existing version
      return createOrUpdateHostVersions(cluster, repoVersionEntity, desiredVersionDefinition,
          stackId, forceInstalled, propertyMap);
    } catch (AmbariException e) {
      throw new SystemException("Can not persist request", e);
    }
  }

  @Transactional
  RequestStatus createOrUpdateHostVersions(Cluster cluster,
      RepositoryVersionEntity repoVersionEntity, VersionDefinitionXml versionDefinitionXml,
      StackId stackId, boolean forceInstalled, Map<String, Object> propertyMap)
      throws AmbariException, SystemException {

    final String desiredRepoVersion = repoVersionEntity.getVersion();

    // get all of the hosts eligible for stack distribution
    List<Host> hosts = Lists.newArrayList(cluster.getHosts());


    for (Host host : hosts) {
      for (HostVersionEntity hostVersion : host.getAllHostVersions()) {
        RepositoryVersionEntity hostRepoVersion = hostVersion.getRepositoryVersion();

        // !!! ignore stack differences
        if (!hostRepoVersion.getStackName().equals(repoVersionEntity.getStackName())) {
          continue;
        }

        int compare = compareVersions(hostRepoVersion.getVersion(), desiredRepoVersion);

        // ignore earlier versions
        if (compare <= 0) {
          continue;
        }

        // !!! the version is greater to the one to install

        // if there is no backing VDF for the desired version, allow the operation (legacy behavior)
        if (null == versionDefinitionXml) {
          continue;
        }

        if (StringUtils.isBlank(versionDefinitionXml.getPackageVersion(host.getOsFamily()))) {
          String msg = String.format("Ambari cannot install version %s.  Version %s is already installed.",
            desiredRepoVersion, hostRepoVersion.getVersion());
          throw new IllegalArgumentException(msg);
        }
      }
    }


    // the cluster will create/update all of the host versions to the correct state
    List<Host> hostsNeedingInstallCommands = cluster.transitionHostsToInstalling(
        repoVersionEntity, versionDefinitionXml, forceInstalled);

    RequestStatusResponse response = null;
    if (!forceInstalled) {
      RequestStageContainer installRequest = createOrchestration(cluster, stackId,
          hostsNeedingInstallCommands, repoVersionEntity, versionDefinitionXml, propertyMap);

      response = installRequest.getRequestStatusResponse();
    }

    return getRequestStatus(response);
  }

  @Transactional
  RequestStageContainer createOrchestration(Cluster cluster, StackId stackId,
      List<Host> hosts, RepositoryVersionEntity repoVersionEnt, VersionDefinitionXml desiredVersionDefinition, Map<String, Object> propertyMap)
      throws AmbariException, SystemException {
    final AmbariManagementController managementController = getManagementController();
    final AmbariMetaInfo ami = managementController.getAmbariMetaInfo();

    // build the list of OS repos
    List<OperatingSystemEntity> operatingSystems = repoVersionEnt.getOperatingSystems();
    Map<String, List<RepositoryEntity>> perOsRepos = new HashMap<>();
    for (OperatingSystemEntity operatingSystem : operatingSystems) {

      if (operatingSystem.isAmbariManagedRepos()) {
        perOsRepos.put(operatingSystem.getOsType(), operatingSystem.getRepositories());
      } else {
        perOsRepos.put(operatingSystem.getOsType(), Collections.<RepositoryEntity> emptyList());
      }
    }

    RequestStageContainer req = createRequest();

    Iterator<Host> hostIterator = hosts.iterator();
    Map<String, String> hostLevelParams = new HashMap<>();
    hostLevelParams.put(JDK_LOCATION, getManagementController().getJdkResourceUrl());
    String hostParamsJson = StageUtils.getGson().toJson(hostLevelParams);

    // Generate cluster host info
    String clusterHostInfoJson;
    try {
      clusterHostInfoJson = StageUtils.getGson().toJson(
        StageUtils.getClusterHostInfo(cluster));
    } catch (AmbariException e) {
      throw new SystemException("Could not build cluster topology", e);
    }

    int maxTasks = configuration.getAgentPackageParallelCommandsLimit();
    int hostCount = hosts.size();
    int batchCount = (int) (Math.ceil((double)hostCount / maxTasks));

    long stageId = req.getLastStageId() + 1;
    if (0L == stageId) {
      stageId = 1L;
    }

    // why does the JSON body parser convert JSON primitives into strings!?
    Float successFactor = INSTALL_PACKAGES_SUCCESS_FACTOR;
    String successFactorProperty = (String) propertyMap.get(CLUSTER_STACK_VERSION_STAGE_SUCCESS_FACTOR);
    if (StringUtils.isNotBlank(successFactorProperty)) {
      successFactor = Float.valueOf(successFactorProperty);
    }

    boolean hasStage = false;

    ArrayList<Stage> stages = new ArrayList<>(batchCount);
    for (int batchId = 1; batchId <= batchCount; batchId++) {
      // Create next stage
      String stageName;
      if (batchCount > 1) {
        stageName = String.format(INSTALL_PACKAGES_FULL_NAME + ". Batch %d of %d", batchId,
            batchCount);
      } else {
        stageName = INSTALL_PACKAGES_FULL_NAME;
      }

      Stage stage = stageFactory.createNew(req.getId(), "/tmp/ambari", cluster.getClusterName(),
          cluster.getClusterId(), stageName, "{}", hostParamsJson);

      // if you have 1000 hosts (10 stages with 100 installs), we want to ensure
      // that a single failure doesn't cause all other stages to abort; set the
      // success factor ratio in order to tolerate some failures in a single
      // stage
      stage.getSuccessFactors().put(Role.INSTALL_PACKAGES, successFactor);

      // set and increment stage ID
      stage.setStageId(stageId);
      stageId++;

      // add the stage that was just created
      stages.add(stage);

      // determine services for the repo
      Set<String> serviceNames = new HashSet<>();

      // !!! limit the serviceNames to those that are detailed for the repository.
      // TODO packages don't have component granularity
      if (RepositoryType.STANDARD != repoVersionEnt.getType()) {
        serviceNames.addAll(desiredVersionDefinition.getAvailableServiceNames());
      }

      // Populate with commands for host
      for (int i = 0; i < maxTasks && hostIterator.hasNext(); i++) {
        Host host = hostIterator.next();
        if (hostHasVersionableComponents(cluster, serviceNames, ami, stackId, host)) {
          ActionExecutionContext actionContext = getHostVersionInstallCommand(repoVersionEnt,
                  cluster, managementController, ami, stackId, serviceNames, perOsRepos, stage, host);
          if (null != actionContext) {
            try {
              actionExecutionHelper.get().addExecutionCommandsToStage(actionContext, stage, null);
              hasStage = true;
            } catch (AmbariException e) {
              throw new SystemException("Cannot modify stage", e);
            }
          }
        }
      }
    }

    if (!hasStage) {
      throw new SystemException(
          String.format("There are no hosts that have components to install for repository %s",
              repoVersionEnt.getDisplayName()));
    }

    req.setClusterHostInfo(clusterHostInfoJson);
    req.addStages(stages);
    req.persist();

    return req;
  }

  private ActionExecutionContext getHostVersionInstallCommand(RepositoryVersionEntity repoVersion,
      Cluster cluster, AmbariManagementController managementController, AmbariMetaInfo ami,
      final StackId stackId, Set<String> repoServices, Map<String, List<RepositoryEntity>> perOsRepos, Stage stage1, Host host)
          throws SystemException {
    // Determine repositories for host
    String osFamily = host.getOsFamily();

    final List<RepositoryEntity> repoInfo = perOsRepos.get(osFamily);
    if (repoInfo == null) {
      throw new SystemException(String.format("Repositories for os type %s are " +
                      "not defined. Repo version=%s, stackId=%s",
        osFamily, repoVersion.getVersion(), stackId));
    }

    if (repoInfo.isEmpty()){
      LOG.error(String.format("Repository list is empty. Ambari may not be managing the repositories for %s", osFamily));
    }

    // determine packages for all services that are installed on host
    Set<String> servicesOnHost = new HashSet<>();
    List<ServiceComponentHost> components = cluster.getServiceComponentHosts(host.getHostName());
    for (ServiceComponentHost component : components) {
      if (repoServices.isEmpty() || repoServices.contains(component.getServiceName())) {
        servicesOnHost.add(component.getServiceName());
      }
    }

    if (servicesOnHost.isEmpty()) {
      return null;
    }


    Map<String, String> roleParams = repoVersionHelper.buildRoleParams(managementController, repoVersion,
        osFamily, servicesOnHost);

    // add host to this stage
    RequestResourceFilter filter = new RequestResourceFilter(null, null,
            Collections.singletonList(host.getHostName()));

    ActionExecutionContext actionContext = new ActionExecutionContext(
            cluster.getClusterName(), INSTALL_PACKAGES_ACTION,
            Collections.singletonList(filter),
            roleParams);
    actionContext.setTimeout(Short.valueOf(configuration.getDefaultAgentTaskTimeout(true)));

    repoVersionHelper.addCommandRepository(actionContext, osFamily, repoVersion, repoInfo);

    return actionContext;

  }


  /**
   * Returns true if there is at least one versionable component on host for a given
   * stack.
   */
  private boolean hostHasVersionableComponents(Cluster cluster, Set<String> serviceNames, AmbariMetaInfo ami, StackId stackId,
      Host host) throws SystemException {

    List<ServiceComponentHost> components = cluster.getServiceComponentHosts(host.getHostName());

    for (ServiceComponentHost component : components) {
      if (!serviceNames.isEmpty() && !serviceNames.contains(component.getServiceName())) {
        continue;
      }

      ComponentInfo componentInfo;
      try {
        componentInfo = ami.getComponent(stackId.getStackName(),
                stackId.getStackVersion(), component.getServiceName(), component.getServiceComponentName());
      } catch (AmbariException e) {
        // It is possible that the component has been removed from the new stack
        // (example: STORM_REST_API has been removed from HDP-2.2)
        LOG.warn(String.format("Exception while accessing component %s of service %s for stack %s",
            component.getServiceComponentName(), component.getServiceName(), stackId));
        continue;
      }
      if (componentInfo.isVersionAdvertised()) {
        return true;
      }
    }
    return false;
  }

  private RequestStageContainer createRequest() {
    ActionManager actionManager = getManagementController().getActionManager();

    RequestStageContainer requestStages = new RequestStageContainer(
            actionManager.getNextRequestId(), null, requestFactory, actionManager);
    requestStages.setRequestContext(String.format(INSTALL_PACKAGES_FULL_NAME));

    return requestStages;
  }

  @Override
  public RequestStatus deleteResourcesAuthorized(Request request, Predicate predicate)
      throws SystemException, UnsupportedPropertyException,
      NoSuchResourceException, NoSuchParentResourceException {
    throw new SystemException("Method not supported");
  }

  @Override
  protected Set<String> getPKPropertyIds() {
    return pkPropertyIds;
  }

  /**
   * Additional check over {@link VersionUtils#compareVersions(String, String)} that
   * compares build numbers
   */
  private static int compareVersions(String version1, String version2) {
    // check _exact_ equality
    if (StringUtils.equals(version1, version2)) {
      return 0;
    }

    int compare = VersionUtils.compareVersions(version1, version2);
    if (0 != compare) {
      return compare;
    }

    int v1 = 0;
    int v2 = 0;
    if (version1.indexOf('-') > -1) {
      v1 = NumberUtils.toInt(version1.substring(version1.indexOf('-')), 0);
    }

    if (version2.indexOf('-') > -1) {
      v2 = NumberUtils.toInt(version2.substring(version2.indexOf('-')), 0);
    }

    compare = v2 - v1;

    return (compare == 0) ? 0 : (compare < 0) ? -1 : 1;
  }


}
