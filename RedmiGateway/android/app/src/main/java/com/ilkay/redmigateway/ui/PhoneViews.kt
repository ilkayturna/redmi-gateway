package com.ilkay.redmigateway.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ilkay.redmigateway.R
import java.util.Locale

/**
 * Rows, avatars and headers shared by the phone screens.
 *
 * The whole list surface follows one 4dp spacing scale and a two-step type ramp
 * (16sp medium title / 14sp secondary line) so recents, contacts and messages all
 * read as the same product. Trailing metadata (time) is a sibling of the title, so
 * it stays optically aligned with the name no matter how tall the row grows.
 */
internal class PhoneViews(private val context: Context) {
    class Avatar(val root: FrameLayout, val image: ImageView, val initial: TextView)

    class Row(
        val root: LinearLayout,
        val avatar: Avatar,
        val title: TextView,
        val subtitleIcon: ImageView,
        val subtitle: TextView,
        val trailing: TextView,
        val action: ImageButton
    )

    /** Shared geometry, in dp. Every screen uses these instead of ad-hoc numbers. */
    object Metrics {
        const val GUTTER = 16
        const val ROW_MIN_HEIGHT = 76
        const val AVATAR_SIZE = 48
        const val TOUCH_TARGET = 48
        const val HEADER_TOP = 20
        const val HEADER_BOTTOM = 8
    }

    private val density = context.resources.displayMetrics.density
    private val photos = LruCache<String, Bitmap>(96)
    private val missingPhotos = HashSet<String>()
    private val turkish = Locale("tr", "TR")

    /**
     * Avatar colours: one muted jade/earth family, not a rainbow.
     *
     * The old set was eight vivid hues (blue, purple, pink, orange) that read as decoration next to
     * the jade accent and made the list feel like a different app. These keep the functional part -
     * a contact's row is recognisable at a glance - by varying hue slightly and lightness more,
     * while every tone stays dark enough to hold 4.5:1 against the white initial on top.
     * Charter: design-system/redmi-gateway/MASTER.md
     */
    private val palette = intArrayOf(
        0xFF2E6B5E.toInt(), 0xFF3F7A6B.toInt(), 0xFF4E6B57.toInt(), 0xFF5A6E64.toInt(),
        0xFF3C6A73.toInt(), 0xFF6B6E5A.toInt(), 0xFF7A6752.toInt(), 0xFF4A5F6E.toInt()
    )

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(value: Int) = (value * density).toInt()

    fun color(id: Int) = ContextCompat.getColor(context, id)

    /** The one weight used for titles, nav labels and section headers. */
    fun mediumTypeface(): Typeface = medium

    fun selectable(borderless: Boolean = false): Drawable? {
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return ContextCompat.getDrawable(context, value.resourceId)
    }

    fun avatar(size: Int = Metrics.AVATAR_SIZE): Avatar {
        val root = FrameLayout(context).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val initial = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 19f
            includeFontPadding = false
            typeface = medium
        }
        val image = ImageView(context)
        root.addView(initial, FrameLayout.LayoutParams(-1, -1))
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        return Avatar(root, image, initial)
    }

    /** Photo when the contact has one, else the first letter on a colour of its own, else a person. */
    fun bindAvatar(avatar: Avatar, name: String?, photoUri: String?) {
        val bitmap = photoUri?.let(::photo)
        val letter = name?.trim()?.firstOrNull { it.isLetterOrDigit() }
        when {
            bitmap != null -> {
                avatar.root.background = ColorDrawable(color(R.color.pb_surface_variant))
                avatar.initial.text = ""
                avatar.image.visibility = View.VISIBLE
                avatar.image.imageTintList = null
                avatar.image.setPadding(0, 0, 0, 0)
                avatar.image.scaleType = ImageView.ScaleType.CENTER_CROP
                avatar.image.setImageBitmap(bitmap)
            }
            letter != null && letter.isLetter() -> {
                avatar.root.background = ColorDrawable(palette[(name.hashCode() and 0x7fffffff) % palette.size])
                avatar.initial.text = letter.toString().uppercase(turkish)
                avatar.image.visibility = View.GONE
                avatar.image.setImageDrawable(null)
            }
            else -> {
                avatar.root.background = ColorDrawable(color(R.color.pb_surface_variant))
                avatar.initial.text = ""
                avatar.image.visibility = View.VISIBLE
                avatar.image.scaleType = ImageView.ScaleType.FIT_CENTER
                avatar.image.setPadding(dp(12), dp(12), dp(12), dp(12))
                avatar.image.setImageResource(R.drawable.ic_pb_person)
                avatar.image.imageTintList = ColorStateList.valueOf(color(R.color.text_muted))
            }
        }
    }

    private fun photo(uri: String): Bitmap? {
        photos.get(uri)?.let { return it }
        if (uri in missingPhotos) return null
        val bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
        }.getOrNull()
        if (bitmap == null) missingPhotos.add(uri) else photos.put(uri, bitmap)
        return bitmap
    }

    fun row(): Row {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Metrics.ROW_MIN_HEIGHT)
            setPadding(dp(Metrics.GUTTER), dp(10), dp(8), dp(10))
            background = selectable()
            layoutParams = AbsListView.LayoutParams(-1, -2)
        }
        val avatar = avatar()
        root.addView(avatar.root, LinearLayout.LayoutParams(dp(Metrics.AVATAR_SIZE), dp(Metrics.AVATAR_SIZE)))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Metrics.GUTTER), 0, dp(8), 0)
        }
        val title = TextView(context).apply {
            textSize = 16f
            typeface = medium
            includeFontPadding = false
            setTextColor(color(R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // Time sits on the name's baseline instead of floating in the middle of the row.
        val trailing = TextView(context).apply {
            textSize = 12f
            includeFontPadding = false
            setTextColor(color(R.color.text_secondary))
            visibility = View.GONE
        }
        val nameLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameLine.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        nameLine.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        texts.addView(nameLine, LinearLayout.LayoutParams(-1, -2))

        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        line.addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
        val subtitle = TextView(context).apply {
            textSize = 14f
            includeFontPadding = false
            setTextColor(color(R.color.text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line.addView(subtitle, LinearLayout.LayoutParams(0, -2, 1f))
        texts.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        root.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        val action = ImageButton(context).apply {
            background = selectable(borderless = true)
            imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            isFocusable = false
            visibility = View.GONE
        }
        root.addView(action, LinearLayout.LayoutParams(dp(Metrics.TOUCH_TARGET), dp(Metrics.TOUCH_TARGET)))
        return Row(root, avatar, title, icon, subtitle, trailing, action)
    }

    /** Section header (day or initial letter). */
    fun header(): TextView = TextView(context).apply {
        textSize = 13f
        typeface = medium
        letterSpacing = 0.06f
        setTextColor(color(R.color.text_muted))
        includeFontPadding = false
        setPadding(dp(Metrics.GUTTER), dp(Metrics.HEADER_TOP), dp(Metrics.GUTTER), dp(Metrics.HEADER_BOTTOM))
        layoutParams = AbsListView.LayoutParams(-1, -2)
        isFocusable = false
    }
}
