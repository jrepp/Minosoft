#!/usr/bin/env bash
#
# Minosoft
# Copyright (C) 2026 Jacob Repp
#
# This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
#
# This software is not affiliated with Mojang AB, the original developer of Minecraft.

set -Eeuo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ -n "${MINOSOFT_JAVA_HOME:-}" ]]; then
    java_bin="$MINOSOFT_JAVA_HOME/bin/java"
elif [[ -x /opt/homebrew/opt/openjdk@25/bin/java ]]; then
    java_bin=/opt/homebrew/opt/openjdk@25/bin/java
elif [[ "$(uname -s)" == Darwin ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    java_home="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
    java_bin="${java_home:+$java_home/bin/java}"
else
    java_bin="$(command -v java || true)"
fi

if [[ -z "${java_bin:-}" || ! -x "$java_bin" ]]; then
    printf 'Error: Java 25 is required. Set MINOSOFT_JAVA_HOME.\n' >&2
    exit 1
fi

java_feature="$("$java_bin" -XshowSettings:properties -version 2>&1 |
    awk -F'= ' '/^[[:space:]]*java.specification.version = / { print $2; exit }')"
if [[ "$java_feature" != 25 ]]; then
    printf 'Error: Java 25 is required, but %s reports Java %s.\n' "$java_bin" "${java_feature:-unknown}" >&2
    exit 1
fi

java_home_dir="$(cd -- "$(dirname -- "$java_bin")/.." && pwd)"
launcher_jar="$project_dir/util/play/build/install/play-util/lib/play-util.jar"
needs_build=false
if [[ ! -f "$launcher_jar" ]]; then
    needs_build=true
elif [[ -n "$(find "$project_dir/util/play" "$project_dir/debug-core/src" "$project_dir/debug-core/build.gradle.kts" \
    -path "$project_dir/util/play/build" -prune -o -type f -newer "$launcher_jar" -print -quit)" ]]; then
    needs_build=true
fi

if [[ "$needs_build" == true ]]; then
    JAVA_HOME="$java_home_dir" PATH="$java_home_dir/bin:$PATH" "$project_dir/gradlew" -q :play-util:installDist
fi

exec "$java_bin" -Dminosoft.project="$project_dir" -cp "$project_dir/util/play/build/install/play-util/lib/*" Play "$@"
