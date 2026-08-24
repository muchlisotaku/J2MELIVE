// =============================================================================
// LCD Handheld — Fragment Shader
// Preset: Classic mobile/handheld LCD (Game Boy, Nokia 3310 style)
// Grid pixel matrix + ghosting + green tint (or cool-white)
// =============================================================================
// u_setting.x = grid_strength    [0.0–1.0]  default 0.50  (pixel border gaps)
// u_setting.y = ghosting         [0.0–0.5]  default 0.15  (motion blur / ghosting)
// u_setting.z = tint_mode        [0.0–2.0]  default 0.0   (0=green,1=grey,2=cold)
// u_setting.w = brightness       [0.5–1.5]  default 0.90  (LCD backlight)
// =============================================================================

precision mediump float;

uniform sampler2D sampler0;
uniform vec2      u_texelDelta;
uniform vec4      u_setting;
uniform vec2      u_pixelDelta;

varying vec2 v_texcoord0;
varying vec2 v_screenpos;

// ── LCD pixel grid ───────────────────────────────────────────────────────────
// Draws a sharp rectangular grid border between each "pixel cell",
// simulating the physical borders of LCD subpixel elements.
// gridStr: 0 = no borders, 1 = thick black borders (very retro)
vec3 lcdGrid(vec3 color, vec2 uv, float gridStr) {
    if (gridStr < 0.01) return color;

    float invTW = 1.0 / u_texelDelta.x;
    float invTH = 1.0 / u_texelDelta.y;

    // Fractional position within each texel [0,1)
    float fx = fract(uv.x * invTW);
    float fy = fract(uv.y * invTH);

    // Border width: gridStr controls how thick the black gap is
    float borderW = gridStr * 0.20;  // up to 20% of pixel width
    float borderH = gridStr * 0.25;  // slightly taller gap (like real LCDs)

    // 1 inside the cell, 0 at the border
    float mx = smoothstep(0.0, borderW, fx) * (1.0 - smoothstep(1.0 - borderW, 1.0, fx));
    float my = smoothstep(0.0, borderH, fy) * (1.0 - smoothstep(1.0 - borderH, 1.0, fy));

    return color * mix(1.0, mx * my, gridStr);
}

// ── LCD ghosting (persistence simulation) ───────────────────────────────────
// LCD pixels don't switch instantly; dark-to-light transitions leave a "ghost".
// We fake this by blending in a slightly offset sample (simulates previous frame).
vec3 ghosting(vec2 uv, vec3 base, float str) {
    if (str < 0.005) return base;
    // Sample diagonally offset — simulates temporal blur
    vec2 off = u_texelDelta * vec2(0.5, 0.3);
    vec3 prev = texture2D(sampler0, uv + off).rgb;
    // Ghost in the darker direction (LCD ghosting: bright→dark is slow)
    return mix(base, max(base, prev * 0.7), str);
}

// ── Colour tint ──────────────────────────────────────────────────────────────
// mode 0: Game Boy green tint
// mode 1: neutral grey / monochrome (Game Boy Pocket)
// mode 2: cold blue-white (Nokia, early colour LCDs)
vec3 applyTint(vec3 color, float mode) {
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    vec3 mono  = vec3(lum);                           // greyscale base

    vec3 gbGreen  = vec3(0.60, 0.73, 0.36) * lum * 1.1;  // classic DMG green
    vec3 gbGrey   = vec3(0.72, 0.78, 0.72) * lum;         // GBP grey
    vec3 coldBlue = vec3(0.78, 0.85, 1.00) * lum;         // Nokia cold white

    if (mode < 0.5) {
        return mix(color, gbGreen, 0.7);
    } else if (mode < 1.5) {
        return mix(color, gbGrey,  0.8);
    } else {
        return mix(color, coldBlue, 0.6);
    }
}

// ── Vignette (subtle for handheld — backlight bleeds outward not inward) ────
float vignette(vec2 p) {
    float r = length(p) * 0.7071;
    return clamp(1.0 - pow(r, 2.2) * 0.35, 0.0, 1.0);
}

void main() {
    float gridStr  = u_setting.x;
    float ghostStr = u_setting.y;
    float tintMode = u_setting.z;
    float bright   = u_setting.w;

    // No barrel distortion for LCD (flat screens)
    vec2 uv = v_texcoord0;

    // Slight screen-edge clamp
    const float E = 0.002;
    float border = smoothstep(0.0, E, uv.x) * smoothstep(1.0, 1.0-E, uv.x)
                 * smoothstep(0.0, E, uv.y) * smoothstep(1.0, 1.0-E, uv.y);
    if (border < 0.001) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    vec3 color = texture2D(sampler0, uv).rgb;

    color = ghosting(uv, color, ghostStr);
    color = lcdGrid(color, uv, gridStr);
    color = applyTint(color, tintMode);
    color *= vignette(v_screenpos);
    color  = clamp(color * bright, 0.0, 1.0);
    color *= border;

    gl_FragColor = vec4(color, 1.0);
}
