#version 330 core

/* RENDERTARGETS: 0 */
in vec2 finUV;
out vec4 foutColor;
uniform sampler2D colortex0;

void main() {
    foutColor = texture(colortex0, finUV);
}
