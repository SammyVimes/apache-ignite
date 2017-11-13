/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

export default ['$scope', 'IgniteVersion', 'IgniteDockerGenerator', function($scope, Version, docker) {
    const ctrl = this;

    this.$onInit = () => {
        // Watchers definition.
        const clusterWatcher = () => {
            delete ctrl.data;

            if (!$scope.cluster)
                return;

            ctrl.data = docker.generate($scope.cluster, Version.currentSbj.getValue());
        };

        // Setup watchers.
        Version.currentSbj.subscribe({
            next: clusterWatcher
        });

        $scope.$watch('cluster', clusterWatcher);
    };
}];
