#!/usr/bin/env bash

# Copyright © 2014-2015 Cask Data, Inc.
# 
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
# 
# http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.
  
# Build script for docs

source ../vars
source ../_common/common-build.sh

CHECK_INCLUDES=${TRUE}

function download_includes() {
  local target_includes_dir=${1}
  
  # Download Apache Sentry File
  local github_source="https://raw.githubusercontent.com/caskdata/cdap-security-extn/${GIT_BRANCH_CDAP_SECURITY_EXTN}/cdap-sentry/cdap-sentry-extension/"
  download_file ${target_includes_dir} ${github_source} README.rst 526e77d7f3176dc3da0a331209359075 cdap-sentry-extension-readme.txt
}

run_command ${1}
