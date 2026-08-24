// =============================================================================
// Arcade RGB Monitor — Fragment Shader
// Preset: Sharp RGB arcade CRT (Toshiba PVM / BVM style)
// Tight aperture grille + minimal curvature + strong mask + RGB sharpening
// =============================================================================
// u_setting.x = scanline_strength  [0.0–1.0]  default 0.40
// u_setting.y = curvature          [0.0–0.06] default 0.02
// u_setting.z = mask_strength      [0.0–1.0]  default 0.55
// u_setting.w = sharpness          [0.0–1.0]  default 0.60  (unsharp mask)
// =============================================================================

precision mediump float;

uniform sampler2D sampler0;
uniform vec2      u_texelDelta;
uniform vec4      u_setting;
uniform vec2      u_pixelDelta;

varying vec2 v_texcoord0;
varying vec2 v_screenpos;

const float PI = 3.14159265;

// ── Minimal barrel (arcade monitors barely curved) ───────────────────────────
vec2 barrel(vec2 p, float k) { return p * (1.0 + k * dot(p, p)); }

// ── Tight aperture grille mask ────────────────────────────────────────────────
// PVM-style: very fine RGB vertical stripes, strong inter-stripe blacks.
// Uses smooth cosine for more accurate phosphor shape (Gaussian dot profile).
vec3 arcadeMask(vec3 color, vec2 uv, float str) {
    if (str < 0.01) return color;
    float invTW = 1.0 / u_texelDelta.x;
    float px    = uv.x * invTW;
    float fPx   = fract(px);        // fractional position within pixel [0,1)

    // Cosine-shaped R/G/B lobes:
    // Red   lobe centred at  0/3  (fPx = 0.0)
    // Green lobe centred at  1/3  (fPx = 0.333)
    // Blue  lobe centred at  2/3  (fPx = 0.667)
    float r = max(0.0, cos((fPx - 0.000) * PI * 3.0));
    float g = max(0.0, cos((fPx - 0.333) * PI * 3.0));
    float b = max(0.0, cos((fPx - 0.667) * PI * 3.0));

    // Blend: at str=0 use original, at str=1 use pure phosphor colours
    float minVal = mix(1.0, 0.10, str);
    float maskR  = mix(1.0, max(r, minVal), str);
    float maskG  = mix(1.0, max(g, minVal), str);
    float maskB  = mix(1.0, max(b, minVal), str);

    return color * vec3(maskR, maskG, maskB);
}

// ── Scanlines ─────────────────────────────────────────────────────────────────
float scanline(vec2 uv, float str) {
    float py = uv.y / u_texelDelta.y;
    float s  = sin(py * PI);
    return mix(1.0 - str * 0.70, 1.0, s * s);
}

// ── Unsharp mask sharpening ───────────────────────────────────────────────────
// Arcade monitors have very sharp phosphors. Boost high-frequency detail
// by adding back a scaled version of (colour - blurred colour).
vec3 sharpen(vec2 uv, vec3 color, float str) {
    if (str < 0.01) return color;
    vec2 d = u_texelDelta;
    vec3 blur = (texture2D(sampler0, uv + vec2( d.x,  0.0)).rgb +
                 texture2D(sampler0, uv + vec2(-d.x,  0.0)).rgb +
                 texture2D(sampler0, uv + vec2( 0.0,  d.y)).rgb +
                 texture2D(sampler0, uv + vec2( 0.0, -d.y)).rgb) * 0.25;
    return clamp(color + (color - blur) * str * 1.5, 0.0, 1.0);
}

// ── RGB bloom (tight, arcade phosphors glow less than TV) ────────────────────
vec3 bloom(vec2 uv, vec3 base, float str) {
    if (str < 0.01) return base;
    vec2 d = u_texelDelta * 1.2;
    vec3 s = (texture2D(sampler0, uv + vec2(d.x, 0.0)).rgb +
              texture2D(sampler0, uv - vec2(d.x, 0.0)).rgb +
              texture2D(sampler0, uv + vec2(0.0, d.y)).rgb +
              texture2D(sampler0, uv - vec2(0.0, d.y)).rgb) * 0.25;
    float lum = dot(s, vec3(0.2126, 0.7152, 0.0722));
    return base + s * smoothstep(0.5, 0.85, lum) * str * 0.25;
}

// ── Subtle vignette (arcade monitor is even lighting) ────────────────────────
float vignette(vec2 p) {
    float r = length(p) * 0.7071;
    return clamp(1.0 - pow(r, 2.0) * 0.30, 0.0, 1.0);
}

void main() {
    float scanStr  = u_setting.x;
    float curvK    = u_setting.y;
    float maskStr  = u_setting.z;
    float sharpStr = u_setting.w;

    vec2 wPos = barrel(v_screenpos, curvK);
    vec2 wUV  = wPos * 0.5 + 0.5;

    const float E = 0.003;
    float border = smoothstep(0.0, E, wUV.x) * smoothstep(1.0, 1.0-E, wUV.x)
                 * smoothstep(0.0, E, wUV.y) * smoothstep(1.0, 1.0-E, wUV.y);
    if (border < 0.001) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    vec3 color = texture2D(sampler0, wUV).rgb;

    // Order: sharpen → bloom → mask → scanlines → vignette
    color = sharpen(wUV, color, sharpStr);
    color = bloom(wUV, color, 0.15);       // fixed subtle bloom for arcade
    color = arcadeMask(color, wUV, maskStr);
    color *= scanline(wUV, scanStr);
    color *= vignette(wPos);
    color *= border;

    // Arcade phosphor: neutral white point (no warm tint)
    // Slightly boost saturation for vivid arcade colours
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(lum), color, 1.15);   // +15% saturation
    color = clamp(color, 0.0, 1.0);

    gl_FragColor = vec4(color, 1.0);
}
