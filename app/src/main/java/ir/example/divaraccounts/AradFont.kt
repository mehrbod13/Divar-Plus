package ir.example.divaraccounts

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/** Loads the bundled Arad faces directly from assets, with resource and system fallbacks. */
object AradFont {
    @Volatile private var regularTypeface: Typeface? = null
    @Volatile private var boldTypeface: Typeface? = null

    fun regular(context: Context): Typeface = regularTypeface ?: synchronized(this) {
        regularTypeface ?: loadTypeface(
            context,
            "fonts/arad_regular.ttf",
            R.font.arad_regular,
            Typeface.create("sans-serif", Typeface.NORMAL)
        ).also { regularTypeface = it }
    }

    fun bold(context: Context): Typeface = boldTypeface ?: synchronized(this) {
        boldTypeface ?: loadTypeface(
            context,
            "fonts/arad_bold.ttf",
            R.font.arad_bold,
            Typeface.create(regular(context), Typeface.BOLD)
        ).also { boldTypeface = it }
    }

    private fun loadTypeface(context: Context, assetPath: String, resourceId: Int, fallback: Typeface): Typeface {
        val appContext = context.applicationContext
        return try {
            Typeface.createFromAsset(appContext.assets, assetPath)
        } catch (_: Exception) {
            try {
                ResourcesCompat.getFont(appContext, resourceId) ?: fallback
            } catch (_: Exception) {
                fallback
            }
        }
    }

    fun apply(context: Context, view: View) {
        if (view is TextView) {
            val currentTypeface = view.typeface
            val style = currentTypeface?.style ?: Typeface.NORMAL
            // Preserve an explicitly assigned bundled Arad Bold face even on devices that
            // report a custom asset typeface's style flag inconsistently.
            val useBold = currentTypeface === boldTypeface ||
                (style and Typeface.BOLD) != 0 || view.paint.isFakeBoldText
            val useItalic = (style and Typeface.ITALIC) != 0
            val base = if (useBold) bold(context) else regular(context)
            view.typeface = if (useItalic) {
                Typeface.create(base, if (useBold) Typeface.BOLD_ITALIC else Typeface.ITALIC)
            } else {
                base
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) apply(context, view.getChildAt(index))
        }
    }
}
