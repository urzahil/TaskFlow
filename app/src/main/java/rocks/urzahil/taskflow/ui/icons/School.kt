/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rocks.urzahil.taskflow.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.School: ImageVector
    get() {
        if (_school != null) {
            return _school!!
        }
        _school = materialIcon(name = "Filled.School") {
            materialPath {
                moveTo(5.0f, 13.18f)
                verticalLineToRelative(4.0f)
                lineTo(12.0f, 21.0f)
                lineToRelative(7.0f, -3.82f)
                verticalLineToRelative(-4.0f)
                lineTo(12.0f, 17.0f)
                lineToRelative(-7.0f, -3.82f)
                close()
                moveTo(12.0f, 3.0f)
                lineTo(1.0f, 9.0f)
                lineToRelative(11.0f, 6.0f)
                lineToRelative(9.0f, -4.91f)
                verticalLineTo(17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(9.0f)
                lineTo(12.0f, 3.0f)
                close()
            }
        }
        return _school!!
    }

private var _school: ImageVector? = null
