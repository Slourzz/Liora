package com.liora.android.ui.player

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size

// ------------------------------------------------------------------ fondo vivo

/**
 * El fondo con vida, escrito en AGSL sobre el algoritmo de kawarp
 * (github.com/better-lyrics/kawarp, MIT): su warp de dominios —dos octavas de
 * ruido simplex lento, con más fuerza en el centro— sobre los dos campos de
 * portada que prepara [AtmosphereFields], su viñeta, su saturación y su grano
 * para que el degradado no se escalone. Aquí, además, el cruce entre los
 * campos lleva una pizca de aberración cromática que se abre hacia los bordes.
 *
 * El campo se muestrea a 128 (el lado al que lo deja [AtmosphereFields]).
 */
private const val SHADER_SOURCE = """
uniform shader texA;
uniform shader texB;
uniform float2 uRes;
uniform float uTime;
uniform float uBlend;
uniform float uWarp;
uniform float uSat;
uniform float uDither;
uniform float uBright;
uniform float uAberration;

float3 m289_3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float2 m289_2(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float3 permute(float3 x) { return m289_3(((x * 34.0) + 1.0) * x); }

float snoise(float2 v) {
  float4 C = float4(0.211324865405187, 0.366025403784439,
                    -0.577350269189626, 0.024390243902439);
  float2 i = floor(v + dot(v, C.yy));
  float2 x0 = v - i + dot(i, C.xx);
  float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
  float4 x12 = x0.xyxy + C.xxzz;
  x12.xy -= i1;
  i = m289_2(i);
  float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
  float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
  m = m * m;
  m = m * m;
  float3 x = 2.0 * fract(p * C.www) - 1.0;
  float3 h = abs(x) - 0.5;
  float3 ox = floor(x + 0.5);
  float3 a0 = x - ox;
  m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
  float3 g;
  g.x = a0.x * x0.x + h.x * x0.y;
  g.yz = a0.yz * x12.xz + h.yz * x12.yw;
  return 130.0 * dot(m, g);
}

float hash13(float3 seed) {
  float3 q = fract(seed * 0.1031);
  q += dot(q, q.zyx + 31.32);
  return fract((q.x + q.y) * q.z);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uRes;
    float t = uTime * 0.05;

    // El warp de kawarp: dos octavas de ruido, más peso en el centro para que
    // las esquinas no se retuerzan.
    float2 center = uv - 0.5;
    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));
    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));
    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));
    float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
    float2 wuv = clamp(uv + warp * uWarp, 0.0, 1.0);

    // El campo, con la pizca de aberración: el rojo mira un poco hacia fuera y
    // el azul hacia dentro, más cuanto más lejos del centro.
    float2 spot = wuv * 128.0;
    float2 dir = center / max(length(center), 0.0001);
    float2 fringe = dir * (uAberration * (0.35 + length(center)));
    float3 red = mix(float3(texA.eval(spot + fringe).rgb), float3(texB.eval(spot + fringe).rgb), uBlend);
    float3 green = mix(float3(texA.eval(spot).rgb), float3(texB.eval(spot).rgb), uBlend);
    float3 blue = mix(float3(texA.eval(spot - fringe).rgb), float3(texB.eval(spot - fringe).rgb), uBlend);
    float3 col = float3(red.r, green.g, blue.b);

    // La salida de kawarp: viñeta, saturación, grano y luz.
    col *= 1.0 - dot(center, center) * 0.3;
    float gray = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(gray), col, uSat);
    float grain = hash13(float3(floor(uv * uRes), floor(uTime * 60.0)));
    col += (grain - 0.5) * uDither;
    col *= uBright;

    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/** Milisegundos entre pasos del flujo: veinte por segundo bastan y sobran. */
internal const val AtmosphereStepMs = 50L

/**
 * A qué fracción de la pantalla se pinta el fondo antes de estirarlo. El campo
 * no tiene detalle que perder —son manchas de color— y estirarlo es casi
 * gratis, así que el shader trabaja sobre una veinticincoava parte de los
 * píxeles.
 */
internal const val AtmosphereRenderScale = 5f

/** Fuerza del warp (kawarp: 1), el color de más (kawarp: 1.5) y su grano. */
private const val WARP = 1f
private const val SATURATION = 1.25f
private const val DITHER = 0.008f

/** Un punto de menos de luz, para que los controles se lean sobre el campo. */
private const val BRIGHTNESS = 0.95f

/** Aberración cromática, en píxeles del campo de 128. */
private const val ABERRATION = 0.45f

/**
 * El shader del fondo, compilado, o null si este Android no lo admite (AGSL
 * llegó en Android 13) o no compila: entonces el fondo dibuja sus campos
 * quietos, que se ven igual pero no se mueven.
 */
@Composable
internal fun rememberAtmosphereShader(): RuntimeShader? = remember {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@remember null
    runCatching { RuntimeShader(SHADER_SOURCE) }.getOrNull()
}

/** Deja el shader apuntando a un lienzo de [size] y a los dos campos. */
internal fun RuntimeShader.paintAtmosphere(
    size: Size,
    seconds: Float,
    blend: Float,
    fieldA: Shader,
    fieldB: Shader,
    darken: Float,
) {
    setInputShader("texA", fieldA)
    setInputShader("texB", fieldB)
    setFloatUniform("uRes", size.width, size.height)
    setFloatUniform("uTime", seconds)
    setFloatUniform("uBlend", blend)
    setFloatUniform("uWarp", WARP)
    setFloatUniform("uSat", SATURATION)
    setFloatUniform("uDither", DITHER)
    setFloatUniform("uBright", BRIGHTNESS * darken)
    setFloatUniform("uAberration", ABERRATION)
}
