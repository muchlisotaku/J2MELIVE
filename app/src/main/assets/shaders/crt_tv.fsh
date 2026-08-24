// =============================================================================
// CRT-TV Retro — Fragment Shader
// Preset: Classic 1980s colour television
// Heavy scanlines + moderate curvature + no mask (TV uses slot mask)
// =============================================================================
// u_setting.x = scanline_strength  [0.0–1.0]  default 0.75
// u_setting.y = curvature          [0.0–0.14] default 0.08
// u_setting.z = brightness_boost   [0.8–1.6]  default 1.10  (TV was brighter)
// u_setting.w = bloom_strength     [0.0–1.0]  default 0.30
// =============================================================================

precision mediump float;

uniform sampler2D sampler0;
uniform vec2      u_texelDelta;
uniform vec4      u_setting;
uniform vec2      u_pixelDelta;

varying vec2 v_texcoord0;
varying vec2 v_screenpos;

const float PI             = 3.14159265;
const float VIGNETTE_POWER = 1.4;
const float VIGNETTE_STR   = 0.65;

// ── Barrel distortion ───────────────────────────────────────────────────────
vec2 barrel(vec2 p, float k) {
    return p * (1.0 + k * dot(p, p));
}

// ── Heavy scanlines (wider dark gap than aperture grille) ───────────────────
// TV scanlines are more pronounced; use sin³ for harder edge
float tvScanline(vec2 uv, float strength) {
    float py = uv.y / u_texelDelta.y;
    float s  = sin(py * PI);
    // sin³: sharper dark band than sin² — more "TV" feel
    float scanline = s * s * abs(s);
    return mix(1.0 - strength * 0.85, 1.0, scanline);
}

// ── Slot mask (horizontal bars between every 2 rows, typical of TV tubes) ──
vec3 slotMask(vec3 color, vec2 uv, float strength) {
    if (strength < 0.01) return color;
    float py = uv.y / u_texelDelta.y;
    // TV slot mask: dark gap every 2 pixel rows
    float row  = mod(floor(py), 2.0);
    float px   = uv.x / u_texelDelta.x;
    float col  = mod(floor(px), 3.0);
    // Slight horizontal modulation + strong vertical slot
    float hMod = (col < 0.5) ? 1.0 : mix(1.0, 0.4, strength * 0.5);
    float vMod = (row < 0.5) ? 1.0 : mix(1.0, 0.15, strength);
    return color * vec3(hMod) * vMod;
}

// ── Bloom ────────────────────────────────────────────────────────────────────
vec3 bloom(vec2 uv, vec3 base, float str) {
    if (str < 0.01) return base;
    vec2 d = u_texelDelta * 2.0;
    vec3 s = (texture2D(sampler0, uv + vec2(d.x, 0.0)).rgb +
              texture2D(sampler0, uv - vec2(d.x, 0.0)).rgb +
              texture2D(sampler0, uv + vec2(0.0, d.y)).rgb +
              texture2D(sampler0, uv - vec2(0.0, d.y)).rgb) * 0.25;
    float lum = dot(s, vec3(0.299, 0.587, 0.114));
    return base + s * smoothstep(0.35, 0.7, lum) * str * 0.5;
}

// ── Vignette ─────────────────────────────────────────────────────────────────
float vignette(vec2 p) {
    float r = length(p) * 0.7071;
    return clamp(mix(1.0, 1.0 - pow(r, VIGNETTE_POWER), VIGNETTE_STR), 0.0, 1.0);
}

void main() {
    float scanStr  = u_setting.x;
    float curvK    = u_setting.y;
    float briBoost = u_setting.z;
    float bloomStr = u_setting.w;

    vec2 wPos = barrel(v_screenpos, curvK);
    vec2 wUV  = wPos * 0.5 + 0.5;

    const float E = 0.004;
    float border = smoothstep(0.0, E, wUV.x) * smoothstep(1.0, 1.0-E, wUV.x)
                 * smoothstep(0.0, E, wUV.y) * smoothstep(1.0, 1.0-E, wUV.y);
    if (border < 0.001) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    vec3 color = texture2D(sampler0, wUV).rgb;

    color  = bloom(wUV, color, bloomStr);
    color  = slotMask(color, wUV, 0.45);
    color *= tvScanline(wUV, scanStr);
    color *= vignette(wPos);
    color *= border;

    // TV brightness boost + slight colour shift (warm yellowish CRT)
    color  = clamp(color * briBoost, 0.0, 1.0);
    color.r = min(color.r * 1.06, 1.0);
    color.g = min(color.g * 1.02, 1.0);
    color.b = color.b * 0.90;

    gl_FragColor = vec4(color, 1.0);
}
