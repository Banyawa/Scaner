package com.banyawa.sitescanner.core.mesh

import com.banyawa.sitescanner.core.pointcloud.RgbImage

/**
 * A mesh with a photo texture: [mesh] with its vertices duplicated wherever two texture
 * charts meet, so each vertex has one texture coordinate; [uv] two per vertex in 0..1
 * with the origin at the image's top left (glTF convention: v runs down); [atlas] the
 * texture image the charts were packed into.
 */
class TexturedMesh(val mesh: TriangleMesh, val uv: FloatArray, val atlas: RgbImage) {
    init {
        require(uv.size == mesh.vertexCount * 2) { "two texture coordinates per vertex" }
    }

    val vertexCount: Int get() = mesh.vertexCount
    val triangleCount: Int get() = mesh.triangleCount

    /** The same texturing on a mesh whose vertices were moved (smoothed, re-framed) but not renumbered. */
    fun withMesh(moved: TriangleMesh): TexturedMesh {
        require(moved.vertexCount == mesh.vertexCount && moved.indices.contentEquals(mesh.indices)) { "same vertices and triangles" }
        return TexturedMesh(moved, uv, atlas)
    }
}
