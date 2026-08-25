/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The handful of fields jterm reads from a GitHub "latest release" response.
 *
 * <p>Every value here is attacker-influenceable text from the network, so nothing is trusted
 * on sight: {@code htmlUrl} is run through {@link UpdateChecker#safeReleaseUrl} before it can
 * reach a browser, and {@code body} is rendered as plain text, never as HTML.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseInfo(@JsonProperty("tag_name") String tagName,
                          @JsonProperty("name") String name,
                          @JsonProperty("html_url") String htmlUrl,
                          @JsonProperty("body") String body) {

    /** The release title if the publisher set one, else the tag. */
    public String displayName() {
        return name == null || name.isBlank() ? String.valueOf(tagName) : name;
    }
}
