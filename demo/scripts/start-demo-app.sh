#!/usr/bin/env bash
#
# Copyright 2026 Sweden Connect
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Starts the demo-app backend with the local Spring profile.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

exec mvn spring-boot:run \
  -f "$PROJECT_DIR/demo/demo-app/backend/pom.xml" \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="-Djavax.net.ssl.trustStore=$PROJECT_DIR/compose/config/common/trust.jks -Djavax.net.ssl.trustStorePassword=secret"
