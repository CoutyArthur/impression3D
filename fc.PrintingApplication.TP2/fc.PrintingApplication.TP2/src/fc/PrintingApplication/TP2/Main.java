package fc.PrintingApplication.TP2;

import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL20.glBindAttribLocation;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;

import javax.imageio.ImageIO;
import javax.swing.text.Segment;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import com.owens.oobjloader.builder.Build;
import com.owens.oobjloader.builder.Face;
import com.owens.oobjloader.builder.FaceVertex;
import com.owens.oobjloader.parser.Parse;

import fc.ChewTriangulation.Pnt;
import fc.ChewTriangulation.Triangulation;
import fc.GLObjects.GLProgram;
import fc.GLObjects.GLRenderTarget;
import fc.GLObjects.GLShaderMatrixParameter;
import fc.Math.AABB;
import fc.Math.Matrix;
import fc.Math.Vec2i;
import fc.Math.Vec3f;

public class Main
{
	//
	// Dans ce TP
	// - rasteriser les perimetres (remplissage de polygone arbitraire)
	// - rasteriser avec la methode de Guthe et al.
	//

	static class Segment
	{
		final Vec3f a, b;
		Segment(float ax, float ay, float bx, float by, float z)
		{
			this.a = new Vec3f(ax, ay, z);
			this.b = new Vec3f(bx, by, z);
		}
	}

	static final float LAYER_HEIGHT = 0.20f;  // pas vertical (mm)
	static final float PIXEL_SIZE   = 0.05f;  // 1 pixel = 0,05 mm
	static final float MARGIN_XY    = 10f;    // 1 cm de chaque côté en X et Y (mm)
	
	public static void checkGLErrorState()
	{
		int err = GL11.glGetError();
		if (err != 0)
			throw new IllegalStateException("OpenGL is in error state " + err);
	}
	
	public static void main(String[] args)
	{
		glfwInit();
		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
		long window = glfwCreateWindow(100, 100, "Dummy", NULL, NULL);
		
		glfwMakeContextCurrent(window);
		GL.createCapabilities();

		GLRenderTarget rt = new GLRenderTarget(600, 400, GL30.GL_RGBA32I, GL30.GL_RGBA_INTEGER, GL11.GL_INT);
		
		GLProgram shader = new GLProgram()
		{
			@Override
			protected void preLinkStep()
			{
				glBindAttribLocation(m_ProgramId, 0, "in_Position");
			}
		};
		shader.init(new MyVShader(), new MyFShader());
		
		GLShaderMatrixParameter matParam = new GLShaderMatrixParameter("u_mvpMatrix");
		matParam.init(shader);
		
		GLVec2iTriangle tri = new GLVec2iTriangle(new Vec2i[]{new Vec2i(0,0), new Vec2i(599,0), new Vec2i(300,300)});
		
		GL11.glViewport(0, 0, 600, 400);
		rt.bind();
        glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		
		shader.begin();
		matParam.set(Matrix.createOrtho(0, 600, 0, 400, -1, 1));
		tri.render();
		shader.end();
		rt.unbind();
		
		float[][][] pixels = rt.readBackAsFloat();
		
		BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
		
		for (int y=0; y < pixels.length; y++)
		{
			for (int x=0; x < pixels[0].length; x++)
			{
				int r = (int)(pixels[y][x][0] * 255.0f);
				int g = (int)(pixels[y][x][1] * 255.0f);
				int b = (int)(pixels[y][x][2] * 255.0f);

				img.setRGB(x, y, (r<<16) | (g<<8) | b);
			}
		}
		rt.dispose();
		
	    File outputFile = new File(System.getProperty("user.dir"), "result.png");
	    try
	    {
	    	ImageIO.write(img, "png", outputFile);
	    }
	    catch (IOException e)
	    {
	    	System.out.println("Error, IOException caught: " + e.toString());
	    }
	}

	static void fillSlice(List<Segment> segments, float boxMinX, float boxMinY,
                      int w, int h, GLRenderTarget rt, GLProgram shader,
                      GLShaderMatrixParameter matParam, File file)
	{
		GL11.glViewport(0, 0, w, h);
		rt.bind();

		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glClearStencil(0);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

		shader.begin();
		matParam.set(Matrix.createOrtho(0, w, 0, h, -1, 1));

		// Passe 1 : incrément du stencil
		GL11.glEnable(GL11.GL_STENCIL_TEST);
		GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL14.GL_INCR_WRAP);
		GL11.glColorMask(false, false, false, false);

		Vec2i o = new Vec2i(0, 0); // point commun, dans la marge de 1 cm
		for (Segment s : segments)
		{
			Vec2i a = new Vec2i(Math.round((s.a.x - boxMinX) / PIXEL_SIZE),
								Math.round((s.a.y - boxMinY) / PIXEL_SIZE));
			Vec2i b = new Vec2i(Math.round((s.b.x - boxMinX) / PIXEL_SIZE),
								Math.round((s.b.y - boxMinY) / PIXEL_SIZE));
			new GLVec2iTriangle(new Vec2i[]{o, a, b}).render();
		}

		// Passe 2 : on peint en blanc là où le stencil est impair
		GL11.glColorMask(true, true, true, true);
		GL11.glStencilFunc(GL11.GL_EQUAL, 1, 1);
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
		new GLVec2iTriangle(new Vec2i[]{new Vec2i(0,0), new Vec2i(w,0), new Vec2i(0,h)}).render();
		new GLVec2iTriangle(new Vec2i[]{new Vec2i(w,0), new Vec2i(w,h), new Vec2i(0,h)}).render();

		shader.end();
		GL11.glDisable(GL11.GL_STENCIL_TEST);
		rt.unbind();

		float[][][] px = rt.readBackAsFloat();
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
			{
				int v = px[y][x][0] > 0.5f ? 0xFFFFFF : 0;
				img.setRGB(x, h - 1 - y, v); // flip : l'origine GL est en bas
			}
		try { ImageIO.write(img, "png", file); } catch (IOException e) { e.printStackTrace(); }
	}

	public void parseObjFile(String filename)
	{
		AABB aa = new AABB();
		List<float[]> triangles = new ArrayList<float[]>();
		
	    try
	    {
	        Build builder = new Build();
	        Parse obj = new Parse(builder, new File(filename).toURI().toURL());
	        
	        // Enumeration des sommets

	        for (FaceVertex vertex : builder.faceVerticeList)
	        {
	        	float x = vertex.v.x;
		    	float y = vertex.v.y;
	        	float z = vertex.v.z;
	        	
				aa.enlarge(new Vec3f(x,y,z));
	        }
			
			aa.addMargin(new Vec3f(10,10,0));

			Vec3f boxMin = aa.getMin();
			Vec3f boxMax = aa.getMax();

	        // Enumeration des faces (souvent des triangles, mais peuvent comporter plus de sommets dans certains cas)
	        
	        for (Face face : builder.faces)
	        {
	        	// Parcours des triangles de cette face
	        	
	        	for (int i=1; i <= (face.vertices.size() - 2); i++)
	        	{
	        		int vertexIndex1 = face.vertices.get(0).index;
	        		int vertexIndex2 = face.vertices.get(i).index;
	        		int vertexIndex3 = face.vertices.get(i+1).index;
	        		
	        		FaceVertex vertex1 = builder.faceVerticeList.get(vertexIndex1);
	        		FaceVertex vertex2 = builder.faceVerticeList.get(vertexIndex2);
	        		FaceVertex vertex3 = builder.faceVerticeList.get(vertexIndex3);

					if(!((vertex1.v.z == vertex2.v.z) && (vertex2.v.z  == vertex3.v.z))){
	        		
						triangles.add(new float[] {
							vertex1.v.x, vertex1.v.y, vertex1.v.z,
							vertex2.v.x, vertex2.v.y, vertex2.v.z,
							vertex3.v.x, vertex3.v.y, vertex3.v.z });
					}
	        	}
	        }
			int width  = (int) Math.ceil((boxMax.x - boxMin.x) / PIXEL_SIZE);
			int height = (int) Math.ceil((boxMax.y - boxMin.y) / PIXEL_SIZE);
			int nbSlices = (int) Math.floor((boxMax.z - boxMin.z) / LAYER_HEIGHT + 1e-4f) + 1;
			File outDir;
			outDir = new File("tranchesOpenGL");
			outDir.mkdirs();

			for (int k = 0; k < nbSlices+1; k++)
			{
				float zCut = boxMin.z + k * LAYER_HEIGHT;
				List<Segment> segments = sliceAtZ(triangles, zCut);

				renderSlice(segments, boxMin.x, boxMax.y, width, height,
						new File(outDir, String.format("tranche_%04d.png", k)));
			}
			System.out.println(nbSlices + " tranches écrites dans " + outDir.getAbsolutePath()
					+ " (bitmaps " + width + "x" + height + ")");
	    }
	    catch (java.io.FileNotFoundException e)
	    {
	    	System.out.println("FileNotFoundException loading file "+filename+", e=" + e);
	        e.printStackTrace();
	    }
	    catch (java.io.IOException e)
	    {
	    	System.out.println("IOException loading file "+filename+", e=" + e);
	        e.printStackTrace();
	    }
	}

	static List<Segment> sliceAtZ(List<float[]> triangles, float zCut)
	{
		List<Segment> segments = new ArrayList<Segment>();

		for (float[] t : triangles)
		{
			// Règle unique : un sommet est "dessus" si z >= zCut, "dessous" sinon
			boolean a0 = t[2] >= zCut;
			boolean a1 = t[5] >= zCut;
			boolean a2 = t[8] >= zCut;

			if (a0 == a1 && a1 == a2)
				continue; // triangle entièrement d'un seul côté : pas d'intersection

			// Exactement 2 arêtes sur 3 traversent le plan
			float[] p = new float[4];
			int n = 0;
			if (a0 != a1) { intersect(t, 0, 1, zCut, p, n); n += 2; }
			if (a1 != a2) { intersect(t, 1, 2, zCut, p, n); n += 2; }
			if (a2 != a0) { intersect(t, 2, 0, zCut, p, n); n += 2; }

			segments.add(new Segment(p[0], p[1], p[2], p[3], zCut));
		}
		return segments;
	}

	static void intersect(float[] t, int i, int j, float zCut, float[] out, int offset)
	{
		float px = t[3*i], py = t[3*i+1], pz = t[3*i+2];
		float qx = t[3*j], qy = t[3*j+1], qz = t[3*j+2];

		// Toujours interpoler du point le plus bas vers le plus haut
		if (pz > qz)
		{
			float tx = px, ty = py, tz = pz;
			px = qx; py = qy; pz = qz;
			qx = tx; qy = ty; qz = tz;
		}

		float s = (zCut - pz) / (qz - pz); // qz != pz garanti (côtés différents)
		out[offset]     = px + s * (qx - px);
		out[offset + 1] = py + s * (qy - py);
	}

	static void renderSlice(List<Segment> segments, float boxMinX, float boxMaxY,
			int width, int height, File file)
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, width, height);
		g.setColor(Color.WHITE);

		for (Segment s : segments)
		{
			g.draw(new Line2D.Float(
					(s.a.x - boxMinX) / PIXEL_SIZE, (boxMaxY - s.a.y) / PIXEL_SIZE,
					(s.b.x - boxMinX) / PIXEL_SIZE, (boxMaxY - s.b.y) / PIXEL_SIZE));
		}
		g.dispose();

		try { ImageIO.write(image, "png", file); }
		catch (Exception e) { e.printStackTrace(); }
	}
	
	
	public void triangulationExample()
	{
        fc.ChewTriangulation.Triangle tri =
    		new fc.ChewTriangulation.Triangle(new Pnt(-10,10), new Pnt(10,10), new Pnt(0,-10));
        
		Triangulation triangulation = new Triangulation(tri);
		
		// Place un nouveau point
		triangulation.delaunayPlace(new Pnt(-9, -9));
		
		// Enumeration des triangles dans la triangulation
		for (fc.ChewTriangulation.Triangle t : triangulation.triGraph.nodeSet())
		{
			// Enumeration des points du triangle
			for (Pnt pnt : t)
			{
				// ...
			}
		}
	}
}
