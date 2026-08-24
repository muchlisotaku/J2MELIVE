// =============================================================================
// CRT Retro Filter v2 — Fragment Shader
// J2ME-Loader  |  OpenGL ES 2.0  |  mediump float
// =============================================================================
//
// ┌─────────────────────────────────────────────────────────────────────────┐
// │  RENDER PIPELINE                                                        │
// │                                                                         │
// │  1. Game renders into FBO (offscreen Bitmap / GL texture)               │
// │  2. repaintScreen() triggers full-screen quad draw                      │
// │  3. ShaderProgram uploads FBO as sampler0 + passes uniforms             │
// │  4. crt.vsh passes UV and NDC position to THIS shader                   │
// │  5. THIS shader applies all CRT effects and writes to default FBO       │
// └─────────────────────────────────────────────────────────────────────────┘
//
// EFFECTS (in execution order):
//   A. Barrel distortion      — convex CRT glass geometry
//   B. Chromatic aberration   — RGB channel fringe at edges
//   C. Phosphor grid mask     — aperture grille sub-pixel structure
//   D. Scanlines (sin²)       — electron beam row gaps
//   E. High-pass bloom        — phosphor halation glow
//   F. Vignette               — corner shadow falloff
//   G. Screen-edge feather    — soft AA border
//   H. Phosphor color temp    — warm CRT white point
//
// UNIFORMS
//   sampler0       — game FBO texture
//   u_texelDelta   — vec2(1/texW, 1/texH)  (set by ShaderProgram.loadVbo)
//   u_pixelDelta   — vec2(1/scrW, 1/scrH)  (set by ShaderProgram)
//   u_setting      — user-adjustable vec4:
//       .x  scanline_strength   [0.0–1.0]  default 0.55
//       .y  curvature           [0.0–0.14] default 0.06
//       .z  mask_strength       [0.0–1.0]  default 0.35
//       .w  bloom_strength      [0.0–1.0]  default 0.20
//
// MOBILE OPTIMISATION NOTES
//   • All branches on uniforms (not varyings) → GPU can predicate-eliminate
//   • Bloom: 4-tap cross, no multi-pass Gaussian → single draw call
//   • Chromatic aberration: 2 extra texture fetches only when k > 0
//   • sin() and pow() are the only transcendentals; mod/floor compile to MAD
//   • No texture2DLod, no derivative instructions → ES 2.0 safe
// =============================================================================

precision mediump float;

// ── Inputs ──────────────────────────────────────────────────────────────────
uniform sampler2D sampler0;
uniform vec2      u_texelDelta;   // (1/texW, 1/texH)
uniform vec4      u_setting;      // x=scan y=curv z=mask w=bloom
uniform vec2      u_pixelDelta;   // (1/scrW, 1/scrH) — unused here but available

varying vec2 v_texcoord0;         // UV [0,1]
varying vec2 v_screenpos;         // NDC [-1,1]

// ── Compile-time tunables (adjust for quality vs speed) ─────────────────────
const float PI               = 3.14159265;
const float VIGNETTE_POWER   = 1.6;    // lower = gentler roll-off
const float VIGNETTE_STRENGTH= 0.60;   // 0=off, 1=full black at corner
const float CHROMA_SCALE     = 0.004;  // chromatic aberration max offset (UV)
const float WARM_R           = 1.05;   // phosphor warmth: boost red
const float WARM_B           = 0.94;   // phosphor warmth: cut blue
const float BLOOM_THRESHOLD  = 0.40;   // luminance threshold for bloom
const float BLOOM_RADIUS     = 1.8;    // bloom sample radius in texels

// =============================================================================
// A. BARREL DISTORTION
//    Physical CRT screens have a slightly convex glass surface.
//    This warps the UV coordinates outward from centre, making the
//    rectangular game image appear to "bow" like a real CRT.
//
//    Model: pincushion-free barrel  p' = p × (1 + k·|p|²)
//    k = curvature uniform (u_setting.y)
//    Typical values: 0.04 (subtle) – 0.12 (strong TV curve)
// =============================================================================
vec2 barrelWarp(vec2 p, float k) {
    return p * (1.0 + k * dot(p, p));
}

// =============================================================================
// B. CHROMATIC ABERRATION
//    CRT lenses refract R/G/B at slightly different angles (lateral CA).
//    We scale each channel's UV outward from centre by a tiny amount:
//      red   →  sample slightly outside (longer wavelength, more refracted)
//      blue  →  sample slightly inside
//    Effect is imperceptible at centre, visible at edges — authentic!
//    Only active when curvature > 0.01 (saves 2 fetches when off).
// =============================================================================
vec3 sampleWithCA(vec2 uv, float strength) {
    vec2 dir   = uv - 0.5;                 // vector from centre
    float dist = length(dir);              // radial distance
    vec2  offs = normalize(dir + vec2(0.0001)) * dist * dist * CHROMA_SCALE * strength;

    float r = texture2D(sampler0, uv + offs).r;
    float g = texture2D(sampler0, uv      ).g;
    float b = texture2D(sampler0, uv - offs).b;
    return vec3(r, g, b);
}

// =============================================================================
// C. PHOSPHOR GRID — APERTURE GRILLE
//    Real CRT phosphors are arranged in vertical R/G/B stripe triplets.
//    We darken the "gap" between stripes using a smooth sine wave.
//
//    Horizontal mask (aperture grille):
//      px = column in texture pixels
//      sin(px × 2π/3): peaks at each R,G,B stripe centre → bright
//                       troughs between stripes          → dark gap
//
//    Vertical mask (shadow mask variant, optional for dot-pitch look):
//      Activated by checking if mask_strength > 0.5 (strong setting)
//      Adds a horizontal component to simulate a shadow-mask dot screen.
//
//    strength: 0 = no mask (pure image), 1 = full phosphor grid
// =============================================================================
vec3 phosphorMask(vec3 color, vec2 uv, float strength) {
    if (strength < 0.01) return color;

    float invTW = 1.0 / u_texelDelta.x;  // texture width in pixels
    float invTH = 1.0 / u_texelDelta.y;  // texture height in pixels

    float px = uv.x * invTW;  // column in texels
    float py = uv.y * invTH;  // row in texels

    // ── Aperture grille: per-column RGB modulation ─────────────────────────
    // sin(px × 2π/3): period = 3 texels, one full R-G-B cycle
    // Map [-1,1] → [darkenedMin, 1.0] using mix
    float col = mod(floor(px), 3.0);  // 0=R 1=G 2=B column
    float minVal = mix(1.0, 0.20, strength);  // darkest inter-stripe value

    // Each channel peaks when its column is "current"
    float maskR = (col < 0.5) ? 1.0 : minVal;
    float maskG = (col > 0.5 && col < 1.5) ? 1.0 : minVal;
    float maskB = (col > 1.5) ? 1.0 : minVal;

    // ── Shadow mask dot component (only for strength > 0.5) ───────────────
    // Adds vertical banding between every other scanline pair
    // Result: 2×3 pixel phosphor dot pattern like a real shadow-mask CRT
    float dotMask = 1.0;
    if (strength > 0.5) {
        float rowParity = mod(floor(py), 2.0);  // alternating rows
        float colShift  = rowParity * 1.5;       // offset every other row
        float shiftedCol = mod(floor(px) + colShift, 3.0);
        float dotR = (shiftedCol < 0.5) ? 1.0 : minVal;
        float dotG = (shiftedCol > 0.5 && shiftedCol < 1.5) ? 1.0 : minVal;
        float dotB = (shiftedCol > 1.5) ? 1.0 : minVal;
        // Blend aperture grille and shadow mask 50/50
        maskR = mix(maskR, dotR, 0.5);
        maskG = mix(maskG, dotG, 0.5);
        maskB = mix(maskB, dotB, 0.5);
    }

    return color * vec3(maskR, maskG, maskB);
}

// =============================================================================
// D. SCANLINES
//    The electron beam sweeps horizontally row by row.  Between rows there
//    is a small dark gap (the beam has finite height, < 1 row pitch).
//
//    sin²(py × π): smoothly oscillates 0→1→0 over each texel row.
//      → maximum (1) at row CENTRE  → full brightness
//      → minimum (0) at row EDGE    → darkened by strength
//
//    This produces smooth, anti-aliased scanlines even when the game
//    texture is scaled to a much larger display.
// =============================================================================
float scanline(vec2 uv, float strength) {
    float py = uv.y / u_texelDelta.y;       // row in texels
    float s  = sin(py * PI);                // sin wave, period = 1 row
    float brightness = s * s;               // sin²: always positive, smooth
    // strength=0 → no effect;  strength=1 → gap is pitch black
    return mix(1.0 - strength, 1.0, brightness);
}

// =============================================================================
// E. HIGH-PASS BLOOM (phosphor halation)
//    Real CRT phosphors glow slightly beyond their pixel boundary.
//    Bright pixels bleed into adjacent dark areas ("halation").
//
//    We use a 4-tap cross sampler to get the local neighbourhood average,
//    then extract only the BRIGHT part (high-pass: lum > threshold)
//    and add it back scaled by bloom_strength.
//
//    4-tap cross at BLOOM_RADIUS texels: cheap approximation of a Gaussian
//    blur that works in a single pass with no intermediate FBO.
// =============================================================================
vec3 bloom(vec2 uv, vec3 baseColor, float strength) {
    if (strength < 0.01) return baseColor;

    vec2 d = u_texelDelta * BLOOM_RADIUS;
    vec3 s0 = texture2D(sampler0, uv + vec2( d.x,  0.0)).rgb;
    vec3 s1 = texture2D(sampler0, uv + vec2(-d.x,  0.0)).rgb;
    vec3 s2 = texture2D(sampler0, uv + vec2( 0.0,  d.y)).rgb;
    vec3 s3 = texture2D(sampler0, uv + vec2( 0.0, -d.y)).rgb;
    // Diagonal samples for fuller 8-tap look (costs 4 more fetches)
    // Disabled by default for mobile — enable if GPU budget allows:
    // vec3 s4 = texture2D(sampler0, uv + vec2( d.x,  d.y)).rgb;
    // vec3 s5 = texture2D(sampler0, uv + vec2(-d.x,  d.y)).rgb;
    // vec3 s6 = texture2D(sampler0, uv + vec2( d.x, -d.y)).rgb;
    // vec3 s7 = texture2D(sampler0, uv + vec2(-d.x, -d.y)).rgb;

    vec3 neighbourhood = (s0 + s1 + s2 + s3) * 0.25;

    // High-pass: extract luminance above threshold
    float lum = dot(neighbourhood, vec3(0.2126, 0.7152, 0.0722));
    float hpMask = smoothstep(BLOOM_THRESHOLD, BLOOM_THRESHOLD + 0.25, lum);

    // Additive blend: bright areas glow outward
    return baseColor + neighbourhood * hpMask * strength * 0.35;
}

// =============================================================================
// F. VIGNETTE
//    Older CRTs had uneven brightness: the electron gun had less energy
//    at the corners of the deflection range, causing edge darkening.
//    We simulate with a radial power curve.
//
//    p: warped NDC position [-1,1]
//    The curve is calibrated so the centre (0,0) = 1.0 (no darkening)
//    and the corner (1,1) approaches (1 - VIGNETTE_STRENGTH).
// =============================================================================
float vignette(vec2 p) {
    float r = length(p) * 0.7071;          // normalise: corner distance = 1
    float v = 1.0 - pow(r, VIGNETTE_POWER);
    return clamp(mix(1.0, v, VIGNETTE_STRENGTH), 0.0, 1.0);
}

// =============================================================================
// MAIN
// =============================================================================
void main() {
    // ── Unpack parameters ───────────────────────────────────────────────────
    float scanStr  = u_setting.x;
    float curvK    = u_setting.y;
    float maskStr  = u_setting.z;
    float bloomStr = u_setting.w;

    // ── A. Barrel warp ──────────────────────────────────────────────────────
    vec2 wPos = barrelWarp(v_screenpos, curvK);
    vec2 wUV  = wPos * 0.5 + 0.5;          // remap [-1,1] → [0,1]

    // ── G. Screen-edge feather (soft AA border) ─────────────────────────────
    // Any UV outside [0,1] after warp is "off-screen glass" → black
    // Use 3px smoothstep feather for a gentle curved-edge anti-alias
    const float EDGE = 0.004;
    float border = smoothstep(0.0,    EDGE,  wUV.x)
                 * smoothstep(1.0, 1.0-EDGE, wUV.x)
                 * smoothstep(0.0,    EDGE,  wUV.y)
                 * smoothstep(1.0, 1.0-EDGE, wUV.y);

    if (border < 0.001) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // ── B. Sample with chromatic aberration ─────────────────────────────────
    vec3 color;
    if (curvK > 0.01) {
        color = sampleWithCA(wUV, curvK * 8.0);
    } else {
        color = texture2D(sampler0, wUV).rgb;
    }

    // ── E. Bloom (before mask so glow bleeds through phosphor gaps) ─────────
    color = bloom(wUV, color, bloomStr);

    // ── C. Phosphor mask ─────────────────────────────────────────────────────
    color = phosphorMask(color, wUV, maskStr);

    // ── D. Scanlines ─────────────────────────────────────────────────────────
    color *= scanline(wUV, scanStr);

    // ── F. Vignette ──────────────────────────────────────────────────────────
    color *= vignette(wPos);

    // ── G. Apply border feather ──────────────────────────────────────────────
    color *= border;

    // ── H. Phosphor colour temperature (warm CRT white point) ────────────────
    // Real P22 phosphors have a slightly warm white point (~6500K).
    // Boost red by 5%, attenuate blue by 6%.
    color = vec3(
        min(color.r * WARM_R, 1.0),
        color.g,
        color.b * WARM_B
    );

    gl_FragColor = vec4(color, 1.0);
}
