/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

angular.module(PKG.name + '.commons')
  .directive('myProgramHistory', function() {
    return {
      restrict: 'EA',
      scope: {
        model: '=runs',
        type: '@',
        appId: '=',
        programId: '='
      },
      templateUrl: 'program-history/program-history.html',
      controller: function ($scope, $state) {

        $scope.appId = $scope.appId || $state.params.appId;
        $scope.programId = $scope.programId || $state.params.programId;

        $scope.currentPage = 1;
        $scope.$watch('model', function (newVal) {
            if (!angular.isArray(newVal)) {
              return;
            }
            $scope.runs = newVal.map(function (run) {
              return angular.extend({
                duration: ( run.end? (run.end - run.start) : 0 )
              }, run);
            });
        });
      }
    };
  });
