// =============================================================================
// CRT Shader — Vertex Stage
// J2ME-Loader Live Editor  |  OpenGL ES 2.0
// =============================================================================
// Pipeline:
//   Game renders → FBO texture (sampler0)
//   This full-screen quad shader samples that texture and passes
//   screen-space coords to the fragment stage for CRT post-processing.
// =============================================================================

attribute vec4 a_position;    // NDC quad: (-1,-1) to (1,1)
attribute vec2 a_texcoord0;   // UV: (0,0) top-left to (1,1) bottom-right

varying vec2 v_texcoord0;     // Pass UV to fragment shader
varying vec2 v_screenpos;     // Pass [-1,1] position for curvature math

void main() {
    gl_Position  = a_position;
    v_texcoord0  = a_texcoord0;
    // Remap [0,1] UV to [-1,1] for symmetric distortion math
    v_screenpos  = a_texcoord0 * 2.0 - 1.0;
}
