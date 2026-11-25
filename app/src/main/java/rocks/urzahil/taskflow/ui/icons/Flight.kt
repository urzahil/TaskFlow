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

public val Icons.Filled.Flight: ImageVector
    get() {
        if (_flight != null) {
            return _flight!!
        }
        _flight = materialIcon(name = "Filled.Flight") {
            materialPath {
                moveTo(21.0f, 16.0f)
                verticalLineToRelative(-2.0f)
                lineToRelative(-8.0f, -5.0f)
                verticalLineTo(3.5f)
                curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveTo(10.0f, 2.67f, 10.0f, 3.5f)
                verticalLineTo(9.0f)
                lineToRelative(-8.0f, 5.0f)
                verticalLineToRelative(2.0f)
                lineToRelative(8.0f, -2.5f)
                verticalLineTo(19.0f)
                lineToRelative(-2.0f, 1.5f)
                verticalLineTo(22.0f)
                lineToRelative(3.5f, -1.0f)
                lineToRelative(3.5f, 1.0f)
                verticalLineToRelative(-1.5f)
                lineTo(13.0f, 19.0f)
                verticalLineToRelative(-5.5f)
                lineToRelative(8.0f, 2.5f)
                close()
            }
        }
        return _flight!!
    }

private var _flight: ImageVector? = null
