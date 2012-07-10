/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
*/

function handleCreateClusterError (errorResponse) {
  globalYui.one("#clusterNameId").addClass('formInputError');
  App.ui.setFormStatus(errorResponse.error, true);
  globalYui.one("#clusterNameId").focus();
}

globalYui.one('#createClusterSubmitButtonId').on('click',function (e) {

      var createClusterData = {
        "clusterName" : globalYui.Lang.trim(globalYui.one("#clusterNameId").get('value')),
      };

      globalYui.log("Cluster Name: "+globalYui.Lang.dump(createClusterData));

      /* Always clear the slate with each submit. */
      App.ui.clearFormStatus();
      globalYui.one("#clusterNameId").removeClass('formInputError');

      App.transition.submitDataAndProgressToNextScreen(
        '../php/frontend/createCluster.php', createClusterData, e.target, 
        '#createClusterCoreDivId', '#addNodesCoreDivId', InstallationWizard.AddNodes.render,
        handleCreateClusterError );
});

/* Signify that the containing application is ready for business. */
App.ui.hideLoadingOverlay();

/* At the end of the installation wizard, we hide 
 * #installationWizardProgressBarDivId, so make sure we explicitly show
 * it at the beginning, to ensure we work correctly when user flow 
 * (potentially) cycles back here.
 */
globalYui.one('#installationWizardProgressBarDivId').setStyle('display', 'block');
