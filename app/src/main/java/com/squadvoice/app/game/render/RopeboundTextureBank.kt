package com.squadvoice.app.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class RopeboundQualityMode { AUTO, LOW, MEDIUM, HIGH }

internal enum class RopeboundQualityTier { LOW, MEDIUM, HIGH }

internal object MatId {
    const val SKY = 0
    const val STONE = 1
    const val WOOD = 2
    const val DIRT = 3
    const val GRASS = 4
    const val BASALT = 5
    const val WATER = 6
    const val WATERFALL = 7
    const val FOLIAGE = 8
    const val VERTEX_COLOR = 9
    const val COUNT = 10
}

/**
 * Manages OpenGL ES 2.0 textures for the 8 core power-of-two WebP environment textures,
 * optional alpha foliage cutout sheet, and the 1x1 white texture used by the vertex-colored batch.
 *
 * Applies per-texture missing-resource fallback with explicit Logcat reporting.
 */
internal class RopeboundTextureBank(private val context: Context) {
    private val texIds = IntArray(MatId.COUNT)
    var loadedTier: RopeboundQualityTier? = null
        private set
    var hasFoliageSheet: Boolean = false
        private set

    fun ensureLoaded(tier: RopeboundQualityTier) {
        if (loadedTier == tier && texIds[MatId.VERTEX_COLOR] != 0) return
        release()
        GLES20.glGenTextures(MatId.COUNT, texIds, 0)

        // 1x1 opaque white texture for MatId.VERTEX_COLOR (runners, rope, untextured hazards)
        val whiteBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, 0xFFFFFFFF.toInt())
        }
        uploadTexture(
            texId = texIds[MatId.VERTEX_COLOR],
            bitmap = whiteBmp,
            wrapS = GLES20.GL_REPEAT,
            wrapT = GLES20.GL_REPEAT,
            useMipmaps = false,
        )
        whiteBmp.recycle()

        // Load each of the 8 core textures + optional foliage sheet with per-texture fallback
        texIds[MatId.SKY] = loadOrSynthesize(
            matId = MatId.SKY,
            resName = "rb_sky_panorama",
            baseW = 2048,
            baseH = 1024,
            tier = tier,
            preferArgb8888 = true,
            wrapS = GLES20.GL_REPEAT,
            wrapT = GLES20.GL_CLAMP_TO_EDGE,
            useMipmaps = false,
        )
        texIds[MatId.STONE] = loadOrSynthesize(
            matId = MatId.STONE,
            resName = "rb_tex_stone",
            baseW = 1024,
            baseH = 1024,
            tier = tier,
            preferArgb8888 = false,
            wrapS = GLES20.GL_REPEAT,
            wrapT = GLES20.GL_REPEAT,
            useMipmaps = true,
        )
        texIds[MatId.WOOD] = loadOrSynthesize(
            matId = MatId.WOOD,
            resName = "rb_tex_wood",
            baseW = 512,
            baseH = 512,
            tier = tier,
            preferArgb8888 = false,
            wrapS = GLES20.GL_REPEAT,
            wrapT = GLES20.GL_REPEAT,
            useMipmaps = true,
        )
        texIds[MatId.DIRT] = loadOrSynthesize(
            matId = MatId.DIRT,
            resName = "rb_tex_dirt",
            baseW = 512,
            baseH = 512,
            tier = tier,
            preferArgb8888 = false,
            wrapS = GLES20.GL_REPEAT,
            wrapT = GLES20.GL_REPEAT,
            useMipmaps = true,
        )
        texIds[MatId.GRASS] = loadOrSynthesize(
            matId = MatId.GRASS,
            resName = "rb_tex_grass",
            baseW = 512,
            baseH = 512,
            tier = tier,
            preferArgb8888 = false,
            wrapS = GLES20.GL_REPEAT,
            wrapT = GLES20.GL_REPEAT,
            useMipmaps = true,
        )
        texIds[MatId.BASALT] = loadOrSynthesize(
            matId = MatId.BASALT,
            resName = "rb_tex_basalt",
            baseW = 512,
            baseH = 512,
            tier = tier,
            preferArgb8888 = false,
            wrapS = GLES20.GL_REPEAT,
            wrapT = GLES20.GL_REPEAT,
            useMipmaps = true,
        )
        texIds[MatId.WATER] = loadOrSynthesize(
            matId = MatId.WATER,
            resName = "rb_tex_water",
            baseW = 1024,
            baseH = 1024,
            tier = tier,
            preferArgb8888 = true,
            wrapS = GLES20.GL_REPEAT,
            wrapT = GLES20.GL_REPEAT,
            useMipmaps = true,
        )
        texIds[MatId.WATERFALL] = loadOrSynthesize(
            matId = MatId.WATERFALL,
            resName = "rb_tex_waterfall",
            baseW = 512,
            baseH = 1024,
            tier = tier,
            preferArgb8888 = false,
            wrapS = GLES20.GL_CLAMP_TO_EDGE,
            wrapT = GLES20.GL_REPEAT,
            useMipmaps = true,
        )

        val foliageResId = context.resources.getIdentifier("rb_foliage_sheet", "drawable", context.packageName)
        if (tier != RopeboundQualityTier.LOW && foliageResId != 0) {
            texIds[MatId.FOLIAGE] = loadOrSynthesize(
                matId = MatId.FOLIAGE,
                resName = "rb_foliage_sheet",
                baseW = 1024,
                baseH = 1024,
                tier = tier,
                preferArgb8888 = true,
                wrapS = GLES20.GL_CLAMP_TO_EDGE,
                wrapT = GLES20.GL_CLAMP_TO_EDGE,
                useMipmaps = true,
            )
            hasFoliageSheet = true
        } else {
            texIds[MatId.FOLIAGE] = texIds[MatId.GRASS]
            hasFoliageSheet = false
            if (foliageResId == 0) {
                Log.i("RopeboundTex", "Optional drawable/rb_foliage_sheet not present — using 3D canopy geometry with rb_tex_grass")
            }
        }

        loadedTier = tier
    }

    fun textureId(matId: Int): Int {
        val id = texIds.getOrElse(matId) { 0 }
        return if (id != 0) id else texIds[MatId.VERTEX_COLOR]
    }

    fun release() {
        if (texIds[MatId.VERTEX_COLOR] != 0) {
            GLES20.glDeleteTextures(MatId.COUNT, texIds, 0)
            texIds.fill(0)
        }
        loadedTier = null
    }

    private fun sampleSizeFor(baseMaxDim: Int, tier: RopeboundQualityTier): Int = when (tier) {
        RopeboundQualityTier.HIGH -> 1
        RopeboundQualityTier.MEDIUM -> if (baseMaxDim >= 1024) 2 else 1
        RopeboundQualityTier.LOW -> if (baseMaxDim >= 1024) 4 else 2
    }

    private fun nearestPowerOfTwo(v: Int): Int {
        var p = 64
        while (p < v && p < 2048) p = p shl 1
        return p
    }

    private fun loadOrSynthesize(
        matId: Int,
        resName: String,
        baseW: Int,
        baseH: Int,
        tier: RopeboundQualityTier,
        preferArgb8888: Boolean,
        wrapS: Int,
        wrapT: Int,
        useMipmaps: Boolean,
    ): Int {
        val texId = texIds[matId]
        val sampleSize = sampleSizeFor(max(baseW, baseH), tier)
        val preferredConfig = if (preferArgb8888) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)

        var bitmap: Bitmap? = null
        if (resId != 0) {
            bitmap = runCatching {
                val opts = BitmapFactory.Options().apply {
                    inScaled = false
                    inSampleSize = sampleSize
                    inPreferredConfig = preferredConfig
                }
                BitmapFactory.decodeResource(context.resources, resId, opts)
            }.getOrNull()
        }

        if (bitmap != null) {
            val potW = nearestPowerOfTwo(bitmap.width)
            val potH = nearestPowerOfTwo(bitmap.height)
            if (bitmap.width != potW || bitmap.height != potH) {
                val scaled = Bitmap.createScaledBitmap(bitmap, potW, potH, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            Log.i("RopeboundTex", "Loaded drawable/$resName (${bitmap.width}x${bitmap.height}, ${bitmap.config}, tier=$tier)")
        } else {
            val fallbackW = max(128, baseW / max(sampleSize, 2))
            val fallbackH = max(128, baseH / max(sampleSize, 2))
            bitmap = synthesizePainterlyFallback(matId, fallbackW, fallbackH, preferredConfig)
            Log.w("RopeboundTex", "Missing drawable/$resName — generated procedural painterly fallback (${fallbackW}x${fallbackH}, $preferredConfig, tier=$tier)")
        }

        uploadTexture(texId, bitmap, wrapS, wrapT, useMipmaps)
        bitmap.recycle()
        return texId
    }

    private fun uploadTexture(
        texId: Int,
        bitmap: Bitmap,
        wrapS: Int,
        wrapT: Int,
        useMipmaps: Boolean,
    ) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            if (useMipmaps) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrapS)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrapT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        if (useMipmaps) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        }
    }

    /**
     * Synthesizes a seamless power-of-two painterly texture when a WebP asset has not yet been dropped into drawable-nodpi.
     */
    private fun synthesizePainterlyFallback(
        matId: Int,
        w: Int,
        h: Int,
        config: Bitmap.Config,
    ): Bitmap {
        val workBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(workBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (matId) {
            MatId.SKY -> {
                // Azure-to-cyan sky gradient + fluffy sunlit cumulus clouds + misty layered alpine peaks
                paint.shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    intArrayOf(0xFF2288E8.toInt(), 0xFF5AB8FA.toInt(), 0xFFA6E4FF.toInt(), 0xFF689BC4.toInt()),
                    floatArrayOf(0f, 0.42f, 0.72f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                paint.shader = null

                // Fluffy cumulus cloud bank
                for (c in 0 until 10) {
                    val cx = (c + 0.5f) * (w / 10f)
                    val cy = h * (0.28f + (c % 3) * 0.07f)
                    val r = w * 0.055f
                    paint.color = 0xFFD4ECFA.toInt()
                    canvas.drawCircle(cx, cy + r * 0.25f, r * 1.15f, paint)
                    paint.color = 0xFFFFFDF8.toInt()
                    canvas.drawCircle(cx, cy, r, paint)
                    canvas.drawCircle(cx - r * 0.65f, cy + r * 0.1f, r * 0.78f, paint)
                    canvas.drawCircle(cx + r * 0.65f, cy + r * 0.12f, r * 0.82f, paint)
                }

                // Misty blue layered mountain silhouettes along bottom third
                for (layer in 0..1) {
                    paint.color = if (layer == 0) 0xFF5C8EB8.toInt() else 0xFF3E6E98.toInt()
                    val baseY = h * (0.76f + layer * 0.08f)
                    val step = w / 16f
                    val path = android.graphics.Path().apply {
                        moveTo(0f, h.toFloat())
                        lineTo(0f, baseY)
                        for (i in 1..16) {
                            val px = i * step
                            val py = baseY - sin((i * 1.7 + layer * 2.3)).toFloat() * (h * 0.11f) - ((i + layer) % 2) * (h * 0.06f)
                            lineTo(px, py)
                        }
                        lineTo(w.toFloat(), h.toFloat())
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
            }

            MatId.STONE -> {
                // Weathered grey-brown ashlar stone masonry with mortar joints & moss flecks
                canvas.drawColor(0xFF423F3B.toInt())
                val rows = 8
                val cols = 4
                val rowH = h / rows.toFloat()
                val colW = w / cols.toFloat()
                for (r in 0 until rows) {
                    val offset = if (r % 2 == 1) colW * 0.5f else 0f
                    for (c in -1..cols) {
                        val bx = c * colW + offset
                        val by = r * rowH
                        val tone = when ((r * 3 + c + 4) % 4) {
                            0 -> 0xFF847E75.toInt()
                            1 -> 0xFF757067.toInt()
                            2 -> 0xFF68635B.toInt()
                            else -> 0xFF7D776D.toInt()
                        }
                        paint.color = tone
                        canvas.drawRoundRect(bx + 3f, by + 3f, bx + colW - 3f, by + rowH - 3f, 8f, 8f, paint)
                        paint.color = 0x44B5AEA2
                        canvas.drawRoundRect(bx + 5f, by + 4f, bx + colW - 5f, by + rowH * 0.35f, 5f, 5f, paint)
                        if ((r + c) % 3 == 0) {
                            paint.color = 0xAA56B828.toInt()
                            canvas.drawCircle(bx + colW * 0.3f, by + 4f, 6f, paint)
                        }
                    }
                }
            }

            MatId.WOOD -> {
                // Warm cedar planks with wood grain & plank seams
                canvas.drawColor(0xFF4A2811.toInt())
                val planks = 4
                val pw = w / planks.toFloat()
                for (p in 0 until planks) {
                    val x0 = p * pw
                    paint.color = if (p % 2 == 0) 0xFFA86028.toInt() else 0xFF945220.toInt()
                    canvas.drawRect(x0 + 2f, 0f, x0 + pw - 2f, h.toFloat(), paint)
                    paint.color = 0x335C2E0E
                    for (g in 0 until 6) {
                        val gx = x0 + 6f + g * ((pw - 12f) / 6f)
                        canvas.drawRect(gx, 0f, gx + 2.5f, h.toFloat(), paint)
                    }
                }
            }

            MatId.DIRT -> {
                // Warm golden-ochre packed earth path with smooth pebbles
                canvas.drawColor(0xFFC88E46.toInt())
                for (i in 0 until 48) {
                    val px = ((i * 73) % w).toFloat()
                    val py = ((i * 137) % h).toFloat()
                    val pr = 6f + (i % 5) * 3.5f
                    paint.color = if (i % 2 == 0) 0xFFD9A45B.toInt() else 0xFFB37A34.toInt()
                    canvas.drawCircle(px, py, pr, paint)
                }
            }

            MatId.GRASS -> {
                // Lush emerald & lime-green mossy turf with sunlit blade strokes
                canvas.drawColor(0xFF3E9420.toInt())
                for (i in 0 until 90) {
                    val gx = ((i * 53) % w).toFloat()
                    val gy = ((i * 97) % h).toFloat()
                    paint.color = when (i % 3) {
                        0 -> 0xFF7CE832.toInt()
                        1 -> 0xFF56BD24.toInt()
                        else -> 0xFF2D7516.toInt()
                    }
                    canvas.drawRoundRect(gx - 4f, gy - 12f, gx + 4f, gy + 12f, 4f, 4f, paint)
                }
            }

            MatId.BASALT -> {
                // Dark slate-charcoal fractured volcanic basalt rock
                canvas.drawColor(0xFF2B2D30.toInt())
                for (i in 0 until 32) {
                    val bx = ((i * 67) % w).toFloat()
                    val by = ((i * 113) % h).toFloat()
                    paint.color = if (i % 2 == 0) 0xFF3B3F44.toInt() else 0xFF222428.toInt()
                    canvas.drawRoundRect(bx - 18f, by - 28f, bx + 18f, by + 28f, 6f, 6f, paint)
                }
            }

            MatId.WATER -> {
                // Turquoise river water with flowing white-water foam ribbons & caustic highlights
                paint.shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(0xFF0E76BE.toInt(), 0xFF1AA6E8.toInt(), 0xFF24C4F8.toInt(), 0xFF0E76BE.toInt()),
                    null,
                    Shader.TileMode.REPEAT,
                )
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                paint.shader = null
                for (r in 0 until 24) {
                    val rx = ((r * 83) % w).toFloat()
                    val ry = ((r * 59) % h).toFloat()
                    val rw = w * 0.16f
                    paint.color = if (r % 3 == 0) 0xDDFFFFFF.toInt() else 0x887AF2FF.toInt()
                    canvas.drawRoundRect(rx - rw, ry - 4f, rx + rw, ry + 4f, 4f, 4f, paint)
                    // Wrap seam duplicate
                    if (rx + rw > w) canvas.drawRoundRect(rx - w - rw, ry - 4f, rx - w + rw, ry + 4f, 4f, 4f, paint)
                }
            }

            MatId.WATERFALL -> {
                // Vertically seamless cascading cyan water and foamy white spray streaks
                canvas.drawColor(0xFF2AC4F6.toInt())
                for (s in 0 until 18) {
                    val sx = (s + 0.5f) * (w / 18f)
                    val sw = w * 0.028f
                    paint.color = if (s % 2 == 0) 0xEEFFFFFF.toInt() else 0xAA8CF4FF.toInt()
                    canvas.drawRect(sx - sw, 0f, sx + sw, h.toFloat(), paint)
                }
            }

            else -> {
                paint.shader = RadialGradient(
                    w * 0.5f, h * 0.5f, w * 0.48f,
                    0xFF68D82E.toInt(), 0xFF266814.toInt(),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
        }

        if (config == Bitmap.Config.RGB_565) {
            val converted = workBmp.copy(Bitmap.Config.RGB_565, false)
            workBmp.recycle()
            return converted ?: workBmp
        }
        return workBmp
    }
}
