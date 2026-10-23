package com.multinfc.manager.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The three glyphs this app needs that are not part of the small
 * `material-icons-core` set. Keeping them here lets us skip the ~57 MB
 * material-icons-extended artifact entirely.
 */
object AppIcons {

    val Nfc: ImageVector by lazy {
        icon("Nfc") {
            // Rounded phone outline.
            stroke {
                moveTo(7f, 3f)
                lineTo(17f, 3f)
                quadTo(21f, 3f, 21f, 7f)
                lineTo(21f, 17f)
                quadTo(21f, 21f, 17f, 21f)
                lineTo(7f, 21f)
                quadTo(3f, 21f, 3f, 17f)
                lineTo(3f, 7f)
                quadTo(3f, 3f, 7f, 3f)
                close()
            }
            // Signal source and radiating waves.
            stroke {
                moveTo(8.4f, 8.6f)
                lineTo(8.4f, 15.4f)
            }
            stroke {
                moveTo(12.4f, 8.4f)
                quadTo(15.3f, 12f, 12.4f, 15.6f)
            }
            stroke {
                moveTo(15.6f, 7.2f)
                quadTo(20.2f, 12f, 15.6f, 16.8f)
            }
        }
    }

    val Notes: ImageVector by lazy {
        icon("Notes") {
            stroke {
                moveTo(6f, 3.5f)
                lineTo(18f, 3.5f)
                quadTo(20.5f, 3.5f, 20.5f, 6f)
                lineTo(20.5f, 18f)
                quadTo(20.5f, 20.5f, 18f, 20.5f)
                lineTo(6f, 20.5f)
                quadTo(3.5f, 20.5f, 3.5f, 18f)
                lineTo(3.5f, 6f)
                quadTo(3.5f, 3.5f, 6f, 3.5f)
                close()
            }
            stroke {
                moveTo(7.5f, 8.5f)
                lineTo(16.5f, 8.5f)
            }
            stroke {
                moveTo(7.5f, 12f)
                lineTo(16.5f, 12f)
            }
            stroke {
                moveTo(7.5f, 15.5f)
                lineTo(13f, 15.5f)
            }
        }
    }

    val ContentCopy: ImageVector by lazy {
        icon("ContentCopy") {
            // Back sheet.
            stroke {
                moveTo(8.5f, 3.5f)
                lineTo(17f, 3.5f)
                quadTo(20.5f, 3.5f, 20.5f, 7f)
                lineTo(20.5f, 15.5f)
            }
            // Front sheet.
            stroke {
                moveTo(15.5f, 8.5f)
                lineTo(7f, 8.5f)
                quadTo(3.5f, 8.5f, 3.5f, 12f)
                lineTo(3.5f, 17f)
                quadTo(3.5f, 20.5f, 7f, 20.5f)
                lineTo(12f, 20.5f)
                quadTo(15.5f, 20.5f, 15.5f, 17f)
                close()
            }
        }
    }
}

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

private fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}
