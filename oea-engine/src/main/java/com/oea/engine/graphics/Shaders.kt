package com.oea.engine.graphics

/** Built-in GLSL ES 3.00 programs. Attribute locations are fixed (see [Mesh]). */
internal object Shaders {
    private const val HEADER = "#version 300 es\nprecision highp float;\n"

    const val ACES = """
vec3 aces(vec3 x) {
    const float a = 2.51; const float b = 0.03; const float c = 2.43; const float d = 0.59; const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}
"""

    // ------------------------------------------------------------------ standard lit/unlit

    val STD_VERT = HEADER + """
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_uv;
layout(location = 3) in vec4 a_color;
layout(location = 4) in vec4 i_m0;
layout(location = 5) in vec4 i_m1;
layout(location = 6) in vec4 i_m2;
layout(location = 7) in vec4 i_m3;
layout(location = 8) in vec4 i_tint;
uniform mat4 u_viewProj;
uniform mat4 u_lightViewProj;
uniform vec4 u_uvTransform;
out vec3 v_worldPos;
out vec3 v_normal;
out vec2 v_uv;
out vec4 v_color;
out vec4 v_lightPos;
void main() {
    mat4 model = mat4(i_m0, i_m1, i_m2, i_m3);
    vec4 world = model * vec4(a_pos, 1.0);
    v_worldPos = world.xyz;
    mat3 m3 = mat3(model);
    vec3 s2 = vec3(dot(m3[0], m3[0]), dot(m3[1], m3[1]), dot(m3[2], m3[2]));
    v_normal = m3 * (a_normal / max(s2, vec3(1e-8)));
    v_uv = a_uv * u_uvTransform.xy + u_uvTransform.zw;
    v_color = a_color * i_tint;
    v_lightPos = u_lightViewProj * world;
    gl_Position = u_viewProj * world;
}
"""

    // ------------------------------------------------------------------ skinned character
    // Same fragment interface as STD_VERT so the lit pipeline is shared.
    // Vertices carry 4 bone indices + weights; the skeleton's uniform palette
    // supplies the matrices. GLSL ES 3.00 allows dynamic indexing here.

    val SKIN_VERT = HEADER + """
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_uv;
layout(location = 3) in vec4 a_color;
layout(location = 4) in vec4 a_boneIds;
layout(location = 5) in vec4 a_boneWeights;
uniform mat4 u_viewProj;
uniform mat4 u_lightViewProj;
uniform mat4 u_model;
uniform mat4 u_bones[19];
uniform vec4 u_uvTransform;
out vec3 v_worldPos;
out vec3 v_normal;
out vec2 v_uv;
out vec4 v_color;
out vec4 v_lightPos;
void main() {
    mat4 skin = u_bones[int(a_boneIds.x)] * a_boneWeights.x
        + u_bones[int(a_boneIds.y)] * a_boneWeights.y
        + u_bones[int(a_boneIds.z)] * a_boneWeights.z
        + u_bones[int(a_boneIds.w)] * a_boneWeights.w;
    vec4 skinned = skin * vec4(a_pos, 1.0);
    vec3 sn = mat3(skin) * a_normal;
    sn = sn / max(sqrt(dot(sn, sn)), 1e-8);
    vec4 world = u_model * skinned;
    v_worldPos = world.xyz;
    mat3 m3 = mat3(u_model);
    vec3 s2 = vec3(dot(m3[0], m3[0]), dot(m3[1], m3[1]), dot(m3[2], m3[2]));
    v_normal = m3 * (sn / max(s2, vec3(1e-8)));
    v_uv = a_uv * u_uvTransform.xy + u_uvTransform.zw;
    v_color = a_color;
    v_lightPos = u_lightViewProj * world;
    gl_Position = u_viewProj * world;
}
"""

    val STD_FRAG = HEADER + """
precision highp sampler2DShadow;
in vec3 v_worldPos;
in vec3 v_normal;
in vec2 v_uv;
in vec4 v_color;
in vec4 v_lightPos;
uniform vec4 u_baseColor;
uniform vec3 u_emissive;
uniform vec4 u_params;      // specular, shininess, rim, unlit
uniform vec4 u_flags;       // texture, receiveShadow, fog, alphaCutoff
uniform sampler2D u_tex;
uniform sampler2DShadow u_shadowMap;
uniform vec3 u_camPos;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_skyAmbient;
uniform vec3 u_groundAmbient;
uniform vec4 u_fog;         // rgb, density
uniform vec4 u_shadowParams; // enabled, texel, bias, strength
// Subsurface scattering: rgb = skin tint (linear), w = strength (0 = off).
uniform vec4 u_subsurface;
uniform float u_exposure;
// Dynamic point lights (fire, sparks, magic, muzzle flashes): the light that
// VFX actually throws onto skin, cloth and terrain.
#define MAX_POINT_LIGHTS 8
uniform vec4 u_pointPos[MAX_POINT_LIGHTS];   // xyz = position, w = radius
uniform vec4 u_pointColor[MAX_POINT_LIGHTS]; // rgb = linear colour × intensity, w = on/off
out vec4 fragColor;
""" + ACES + """
float shadowFactor(vec3 n) {
    vec3 p = v_lightPos.xyz / v_lightPos.w * 0.5 + 0.5;
    if (p.x <= 0.001 || p.x >= 0.999 || p.y <= 0.001 || p.y >= 0.999 || p.z >= 1.0) return 1.0;
    float t = u_shadowParams.y;
    float bias = u_shadowParams.z * (1.0 + 2.0 * (1.0 - clamp(dot(n, u_sunDir), 0.0, 1.0)));
    float z = p.z - bias;
    float s = texture(u_shadowMap, vec3(p.xy + vec2(-0.75, -0.75) * t, z));
    s += texture(u_shadowMap, vec3(p.xy + vec2(0.75, -0.75) * t, z));
    s += texture(u_shadowMap, vec3(p.xy + vec2(-0.75, 0.75) * t, z));
    s += texture(u_shadowMap, vec3(p.xy + vec2(0.75, 0.75) * t, z));
    return mix(1.0, s * 0.25, u_shadowParams.w);
}
void main() {
    vec4 base = u_baseColor * v_color;
    if (u_flags.x > 0.5) {
        vec4 t = texture(u_tex, v_uv);
        // Android uploads bitmaps premultiplied; lighting wants straight colour.
        if (t.a > 0.0) t.rgb /= t.a;
        t.rgb = pow(max(t.rgb, vec3(0.0)), vec3(2.2));
        base *= t;
    }
    if (base.a < u_flags.w) discard;
    vec3 color;
    if (u_params.w > 0.5) {
        color = base.rgb + u_emissive;
        if (u_flags.z > 0.5 && u_fog.a > 0.0) {
            float d = length(u_camPos - v_worldPos) * u_fog.a;
            color = mix(color, u_fog.rgb, clamp(1.0 - exp(-d * d), 0.0, 1.0));
        }
        fragColor = vec4(pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2)), base.a);
        return;
    }
    vec3 n = normalize(v_normal);
    if (!gl_FrontFacing) n = -n;
    vec3 v = normalize(u_camPos - v_worldPos);
    float ndl = max(dot(n, u_sunDir), 0.0);
    float shadow = 1.0;
    if (u_shadowParams.x > 0.5 && u_flags.y > 0.5 && ndl > 0.0) shadow = shadowFactor(n);
    vec3 h = normalize(u_sunDir + v);
    float spec = pow(max(dot(n, h), 0.0), u_params.y) * u_params.x * ndl;
    vec3 ambient = mix(u_groundAmbient, u_skyAmbient, n.y * 0.5 + 0.5);
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * u_params.z;
    color = base.rgb * (ambient + u_sunColor * ndl * shadow) + u_sunColor * spec * shadow + rim * u_skyAmbient + u_emissive;
    // Point lights: every flame, spark and spell throws real light around it.
    for (int i = 0; i < MAX_POINT_LIGHTS; i++) {
        float on = u_pointColor[i].w;
        if (on < 0.001) continue;
        vec3 ld = u_pointPos[i].xyz - v_worldPos;
        float d2 = dot(ld, ld);
        float rad = u_pointPos[i].w;
        float att = clamp(1.0 - d2 / max(rad * rad, 1e-4), 0.0, 1.0);
        att *= att;
        vec3 ln = ld * inversesqrt(max(d2, 1e-4));
        float ndl2 = max(dot(n, ln), 0.0);
        vec3 h2 = normalize(ln + v);
        float spec2 = pow(max(dot(n, h2), 0.0), u_params.y) * u_params.x;
        color += u_pointColor[i].rgb * on * att * (base.rgb * ndl2 + vec3(spec2) * 0.7 + base.rgb * 0.08);
    }
    // Subsurface scattering: light that leaks through thin skin — the cheek,
    // ear and nose glow warm when the sun is behind. One term kills the
    // "plastic statue" look on faces and bare skin.
    if (u_subsurface.w > 0.01) {
        float sss = pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 3.0);
        color += u_subsurface.rgb * sss * u_subsurface.w;
    }
    if (u_flags.z > 0.5 && u_fog.a > 0.0) {
        float d = length(u_camPos - v_worldPos) * u_fog.a;
        color = mix(color, u_fog.rgb, clamp(1.0 - exp(-d * d), 0.0, 1.0));
    }
    color = aces(color * u_exposure);
    fragColor = vec4(pow(color, vec3(1.0 / 2.2)), base.a);
}
"""

    // ------------------------------------------------------------------ shadow depth

    val DEPTH_VERT = HEADER + """
layout(location = 0) in vec3 a_pos;
layout(location = 4) in vec4 i_m0;
layout(location = 5) in vec4 i_m1;
layout(location = 6) in vec4 i_m2;
layout(location = 7) in vec4 i_m3;
uniform mat4 u_lightViewProj;
void main() {
    gl_Position = u_lightViewProj * (mat4(i_m0, i_m1, i_m2, i_m3) * vec4(a_pos, 1.0));
}
"""

    val DEPTH_FRAG = HEADER + """
out vec4 fragColor;
void main() { fragColor = vec4(1.0); }
"""

    // ------------------------------------------------------------------ sky

    val SKY_VERT = HEADER + """
layout(location = 0) in vec2 a_pos;
out vec2 v_ndc;
void main() {
    v_ndc = a_pos;
    gl_Position = vec4(a_pos, 1.0, 1.0);
}
"""

    val SKY_FRAG = HEADER + """
in vec2 v_ndc;
uniform mat4 u_invViewProj;
uniform vec3 u_camPos;
uniform vec3 u_skyTop;
uniform vec3 u_skyHorizon;
uniform vec3 u_ground;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform float u_sunSize;
uniform float u_exposure;
uniform float u_time;
out vec4 fragColor;
""" + ACES + """
void main() {
    vec4 far = u_invViewProj * vec4(v_ndc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w - u_camPos);
    float h = dir.y;
    vec3 col = h >= 0.0 ? mix(u_skyHorizon, u_skyTop, pow(clamp(h, 0.0, 1.0), 0.55))
                        : mix(u_skyHorizon, u_ground, pow(clamp(-h, 0.0, 1.0), 0.35));
    float s = max(dot(dir, u_sunDir), 0.0);
    col += u_sunColor * (pow(s, 1400.0 / u_sunSize) * 6.0 + pow(s, 12.0) * 0.18);
    col = aces(col * u_exposure);
    fragColor = vec4(pow(col, vec3(1.0 / 2.2)), 1.0);
}
"""

    // ------------------------------------------------------------------ 2D sprites (premultiplied alpha)

    val SPRITE_VERT = HEADER + """
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec4 a_color;
uniform mat4 u_proj;
out vec2 v_uv;
out vec4 v_color;
void main() {
    v_uv = a_uv;
    v_color = vec4(a_color.rgb * a_color.a, a_color.a);
    gl_Position = u_proj * vec4(a_pos, 0.0, 1.0);
}
"""

    val SPRITE_FRAG = "#version 300 es\nprecision mediump float;\n" + """
uniform sampler2D u_tex;
in vec2 v_uv;
in vec4 v_color;
out vec4 fragColor;
void main() { fragColor = texture(u_tex, v_uv) * v_color; }
"""

    // ------------------------------------------------------------------ particles (instanced billboards)

    val PARTICLE_VERT = HEADER + """
layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec4 i_posSize;
layout(location = 2) in vec4 i_color;
layout(location = 3) in vec2 i_rotStretch;
uniform mat4 u_viewProj;
uniform vec3 u_camRight;
uniform vec3 u_camUp;
out vec2 v_uv;
out vec4 v_color;
void main() {
    float c = cos(i_rotStretch.x);
    float s = sin(i_rotStretch.x);
    vec2 corner = vec2(a_corner.x, a_corner.y * i_rotStretch.y);
    vec2 r = vec2(corner.x * c - corner.y * s, corner.x * s + corner.y * c);
    vec3 world = i_posSize.xyz + (u_camRight * r.x + u_camUp * r.y) * i_posSize.w;
    v_uv = a_corner * 0.5 + 0.5;
    v_color = vec4(i_color.rgb * i_color.a, i_color.a);
    gl_Position = u_viewProj * vec4(world, 1.0);
}
"""

    val PARTICLE_FRAG = "#version 300 es\nprecision mediump float;\n" + """
uniform sampler2D u_tex;
uniform float u_additive;
in vec2 v_uv;
in vec4 v_color;
out vec4 fragColor;
void main() {
    vec4 t = texture(u_tex, v_uv) * v_color;
    fragColor = vec4(t.rgb, t.a * (1.0 - u_additive));
}
"""

    // ------------------------------------------------------------------ post processing

    val FULLSCREEN_VERT = HEADER + """
layout(location = 0) in vec2 a_pos;
uniform vec2 u_uvScale;
out vec2 v_uv;
void main() {
    v_uv = (a_pos * 0.5 + 0.5) * u_uvScale;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
"""

    /** Bright pass with soft knee and 4-tap downsample. */
    val BRIGHT_FRAG = HEADER + """
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_texel;
uniform float u_threshold;
out vec4 fragColor;
void main() {
    vec3 c = texture(u_tex, v_uv + vec2(-u_texel.x, -u_texel.y)).rgb;
    c += texture(u_tex, v_uv + vec2(u_texel.x, -u_texel.y)).rgb;
    c += texture(u_tex, v_uv + vec2(-u_texel.x, u_texel.y)).rgb;
    c += texture(u_tex, v_uv + vec2(u_texel.x, u_texel.y)).rgb;
    c *= 0.25;
    float l = max(c.r, max(c.g, c.b));
    float knee = 0.18;
    float soft = clamp(l - u_threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float contrib = max(soft, l - u_threshold) / max(l, 1e-4);
    fragColor = vec4(c * contrib, 1.0);
}
"""

    /** 9-tap Gaussian using bilinear taps. */
    val BLUR_FRAG = HEADER + """
in vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_dir;
uniform vec2 u_clamp;
out vec4 fragColor;
void main() {
    vec2 o1 = u_dir * 1.3846153846;
    vec2 o2 = u_dir * 3.2307692308;
    vec3 c = texture(u_tex, v_uv).rgb * 0.2270270270;
    c += texture(u_tex, min(v_uv + o1, u_clamp)).rgb * 0.3162162162;
    c += texture(u_tex, max(v_uv - o1, vec2(0.0))).rgb * 0.3162162162;
    c += texture(u_tex, min(v_uv + o2, u_clamp)).rgb * 0.0702702703;
    c += texture(u_tex, max(v_uv - o2, vec2(0.0))).rgb * 0.0702702703;
    fragColor = vec4(c, 1.0);
}
"""

    /** Final composite: FXAA, light sharpening when upscaling, bloom, vignette, dithering. */
    val COMPOSITE_FRAG = HEADER + """
in vec2 v_uv;
uniform sampler2D u_scene;
uniform sampler2D u_bloom;
uniform vec2 u_texel;
uniform vec2 u_bloomUvScale;
uniform vec4 u_params;   // fxaa, bloomStrength, vignette, sharpen
uniform vec2 u_uvMax;
uniform float u_time;
out vec4 fragColor;
vec3 tap(vec2 uv) { return texture(u_scene, clamp(uv, vec2(0.0), u_uvMax)).rgb; }
vec3 fxaa(vec2 uv) {
    vec3 rgbNW = tap(uv + vec2(-1.0, -1.0) * u_texel);
    vec3 rgbNE = tap(uv + vec2(1.0, -1.0) * u_texel);
    vec3 rgbSW = tap(uv + vec2(-1.0, 1.0) * u_texel);
    vec3 rgbSE = tap(uv + vec2(1.0, 1.0) * u_texel);
    vec3 rgbM = tap(uv);
    vec3 luma = vec3(0.299, 0.587, 0.114);
    float lNW = dot(rgbNW, luma); float lNE = dot(rgbNE, luma);
    float lSW = dot(rgbSW, luma); float lSE = dot(rgbSE, luma);
    float lM = dot(rgbM, luma);
    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
    if (lMax - lMin < max(0.0312, lMax * 0.125)) return rgbM;
    vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));
    float reduce = max((lNW + lNE + lSW + lSE) * (0.25 * 0.125), 1.0 / 128.0);
    float rcpMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * rcpMin, vec2(-8.0), vec2(8.0)) * u_texel;
    vec3 a = 0.5 * (tap(uv + dir * (1.0 / 3.0 - 0.5)) + tap(uv + dir * (2.0 / 3.0 - 0.5)));
    vec3 b = a * 0.5 + 0.25 * (tap(uv + dir * -0.5) + tap(uv + dir * 0.5));
    float lb = dot(b, luma);
    return (lb < lMin || lb > lMax) ? a : b;
}
void main() {
    vec3 c = u_params.x > 0.5 ? fxaa(v_uv) : tap(v_uv);
    if (u_params.w > 0.0) {
        vec3 blur = (tap(v_uv + vec2(u_texel.x, 0.0)) + tap(v_uv - vec2(u_texel.x, 0.0)) +
                     tap(v_uv + vec2(0.0, u_texel.y)) + tap(v_uv - vec2(0.0, u_texel.y))) * 0.25;
        c = clamp(c + (c - blur) * u_params.w, 0.0, 1.0);
    }
    if (u_params.y > 0.0) {
        vec2 buv = v_uv / u_uvMax * u_bloomUvScale;
        c += texture(u_bloom, buv).rgb * u_params.y;
    }
    if (u_params.z > 0.0) {
        vec2 p = v_uv / u_uvMax - 0.5;
        c *= 1.0 - u_params.z * dot(p, p) * 1.6;
    }
    float n = fract(sin(dot(gl_FragCoord.xy + u_time, vec2(12.9898, 78.233))) * 43758.5453);
    c += (n - 0.5) / 255.0;
    fragColor = vec4(c, 1.0);
}
"""
}
