package fc.PrintingApplication.TP2;

import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL20.glBindAttribLocation;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import com.owens.oobjloader.builder.Build;
import com.owens.oobjloader.builder.Face;
import com.owens.oobjloader.builder.FaceVertex;
import com.owens.oobjloader.parser.Parse;

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
	// TP3 :
	// - slicing du TP1 (sliceAtZ) puis chaînage des segments en contours fermés
	// - remplissage GPU par la méthode de Guthe et al. :
	//     1) chaque contour -> éventail de triangles (premier point du contour = sommet commun)
	//     2) rasterisation dans le stencil buffer en mode incrémentation (couleur masquée)
	//     3) un quad plein écran est dessiné là où stencil est impair
	//     4) readback de la render target -> PNG
	// - le rasteriseur CPU scanline du TP1 est conservé pour comparer les résultats
	//

	static final float LAYER_HEIGHT = 0.20f;  // pas vertical (mm)
	static final float PIXEL_SIZE   = 0.05f;  // 1 pixel = 0,05 mm
	static final float MARGIN_XY    = 10f;    // 1 cm de chaque côté en X et Y (mm)

	static final boolean GPU = true;          // true : remplissage GPU, false : scanline CPU (TP1)

	// Le readback OpenGL commence par la ligne du bas (origine GL en bas à gauche).
	// Si les PNG GPU sortent à l'envers, passer à false.
	static final boolean FLIP_READBACK = true;

	// Ressources OpenGL (initialisées une fois la taille des bitmaps connue)
	GLRenderTarget rt;
	GLProgram shader;
	GLShaderMatrixParameter matParam;

	public static void checkGLErrorState()
	{
		int err = GL11.glGetError();
		if (err != 0)
			throw new IllegalStateException("OpenGL is in error state " + err);
	}

	static void checkStencil()
	{
		int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
				GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		System.out.println("stencil attaché : " + (type != GL11.GL_NONE)
				+ " | FBO complet : " + (status == GL30.GL_FRAMEBUFFER_COMPLETE));
	}

	/** Segment de tranche : tous les sommets partagent la même cote Z. */
	static class Segment
	{
		final Vec3f a, b;
		Segment(float ax, float ay, float bx, float by, float z)
		{
			this.a = new Vec3f(ax, ay, z);
			this.b = new Vec3f(bx, by, z);
		}
	}

	// ------------------------------------------------------------------
	// main : contexte OpenGL invisible (pas de rendu écran)
	// ------------------------------------------------------------------

	public static void main(String[] args)
	{
		glfwInit();
		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
		long window = glfwCreateWindow(100, 100, "Dummy", NULL, NULL);

		glfwMakeContextCurrent(window);
		GL.createCapabilities();

		new Main().parseObjFile("3d_models/CuteOcto.obj");

		GLFW.glfwDestroyWindow(window);
		GLFW.glfwTerminate();
	}

	void initGL(int width, int height)
	{
		rt = new GLRenderTarget(width, height, GL30.GL_RGBA32I, GL30.GL_RGBA_INTEGER, GL11.GL_INT);

		shader = new GLProgram()
		{
			@Override
			protected void preLinkStep()
			{
				glBindAttribLocation(m_ProgramId, 0, "in_Position");
			}
		};
		shader.init(new MyVShader(), new MyFShader());

		matParam = new GLShaderMatrixParameter("u_mvpMatrix");
		matParam.init(shader);
		rt.bind();
		int rb = GL30.glGenRenderbuffers();
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rb);
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
				GL30.GL_RENDERBUFFER, rb);
		rt.unbind();
	}

	// ------------------------------------------------------------------
	// Lecture OBJ + boucle sur les tranches
	// ------------------------------------------------------------------

	public void parseObjFile(String filename)
	{
		AABB aa = new AABB();
		List<float[]> triangles = new ArrayList<float[]>();

		try
		{
			Build builder = new Build();
			Parse obj = new Parse(builder, new File(filename).toURI().toURL());

			for (FaceVertex vertex : builder.faceVerticeList)
				aa.enlarge(new Vec3f(vertex.v.x, vertex.v.y, vertex.v.z));

			aa.addMargin(new Vec3f(MARGIN_XY, MARGIN_XY, 0));

			Vec3f boxMin = aa.getMin();
			Vec3f boxMax = aa.getMax();

			for (Face face : builder.faces)
			{
				for (int i = 1; i <= (face.vertices.size() - 2); i++)
				{
					FaceVertex vertex1 = builder.faceVerticeList.get(face.vertices.get(0).index);
					FaceVertex vertex2 = builder.faceVerticeList.get(face.vertices.get(i).index);
					FaceVertex vertex3 = builder.faceVerticeList.get(face.vertices.get(i + 1).index);

					// Triangles horizontaux ignorés (pas d'intersection exploitable)
					if (!((vertex1.v.z == vertex2.v.z) && (vertex2.v.z == vertex3.v.z)))
					{
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

			File outDir = new File(GPU ? "tranchesGPU" : "tranchesCPU");
			outDir.mkdirs();

			if (GPU)
				initGL(width, height);

			for (int k = 0; k < nbSlices; k++)
			{
				float zCut = boxMin.z + k * LAYER_HEIGHT;
				List<Segment> segments = sliceAtZ(triangles, zCut);
				File out = new File(outDir, String.format("tranche_%04d.png", k));

				if (GPU)
				{
					List<List<float[]>> contours = buildContours(segments);
					float[][][] pixels = fillSliceGPU(contours, boxMin.x, boxMin.y, width, height);
					writePng(pixels, out);
				}
				else
					rasterEdge(segments, boxMin.x, boxMax.y, width, height, out);
			}

			if (GPU)
				rt.dispose();

			System.out.println(nbSlices + " tranches écrites dans " + outDir.getAbsolutePath()
					+ " (bitmaps " + width + "x" + height + ")");
		}
		catch (java.io.FileNotFoundException e)
		{
			System.out.println("FileNotFoundException loading file " + filename + ", e=" + e);
			e.printStackTrace();
		}
		catch (java.io.IOException e)
		{
			System.out.println("IOException loading file " + filename + ", e=" + e);
			e.printStackTrace();
		}
	}

	// ------------------------------------------------------------------
	// Slicing (TP1)
	// ------------------------------------------------------------------

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
		float px = t[3 * i], py = t[3 * i + 1], pz = t[3 * i + 2];
		float qx = t[3 * j], qy = t[3 * j + 1], qz = t[3 * j + 2];

		// Toujours interpoler du point le plus bas vers le plus haut
		// (garantit que deux triangles voisins calculent exactement le même point)
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

	// ------------------------------------------------------------------
	// Chaînage des segments en contours fermés
	// ------------------------------------------------------------------

	static long key(float x, float y)
	{
		return ((long) Float.floatToIntBits(x) << 32) | (Float.floatToIntBits(y) & 0xFFFFFFFFL);
	}

	/**
	 * Relie les segments (non ordonnés) en polylignes fermées.
	 * Deux triangles voisins calculent le même point d'intersection au bit près,
	 * donc l'égalité exacte des flottants suffit pour apparier les extrémités.
	 */
	static List<List<float[]>> buildContours(List<Segment> segs)
	{
		List<List<float[]>> contours = new ArrayList<List<float[]>>();

		Map<Long, List<Integer>> adj = new HashMap<Long, List<Integer>>();
		for (int i = 0; i < segs.size(); i++)
		{
			Segment s = segs.get(i);
			adj.computeIfAbsent(key(s.a.x, s.a.y), k -> new ArrayList<Integer>()).add(i);
			adj.computeIfAbsent(key(s.b.x, s.b.y), k -> new ArrayList<Integer>()).add(i);
		}

		boolean[] used = new boolean[segs.size()];

		for (int i = 0; i < segs.size(); i++)
		{
			if (used[i])
				continue;
			used[i] = true;

			Segment s = segs.get(i);
			long startKey = key(s.a.x, s.a.y);
			List<float[]> contour = new ArrayList<float[]>();
			contour.add(new float[] { s.a.x, s.a.y });

			float cx = s.b.x, cy = s.b.y;
			while (key(cx, cy) != startKey) // arrêt dès que le contour se referme
			{
				contour.add(new float[] { cx, cy });

				int next = -1;
				for (int j : adj.get(key(cx, cy)))
				{
					if (!used[j]) { next = j; break; }
				}
				if (next < 0)
					break; // contour ouvert (maillage non étanche) : on garde ce qu'on a

				used[next] = true;
				Segment t = segs.get(next);
				if (key(t.a.x, t.a.y) == key(cx, cy)) { cx = t.b.x; cy = t.b.y; }
				else                                  { cx = t.a.x; cy = t.a.y; }
			}

			if (contour.size() >= 3)
				contours.add(contour);
		}
		return contours;
	}

	// ------------------------------------------------------------------
	// Remplissage GPU (Guthe et al.)
	// ------------------------------------------------------------------

	static Vec2i[] toPixels(List<float[]> contour, float boxMinX, float boxMinY)
	{
		Vec2i[] pts = new Vec2i[contour.size()];
		for (int i = 0; i < pts.length; i++)
		{
			float[] p = contour.get(i);
			// Repère OpenGL (y vers le haut) : 1 unité = 1 pixel
			pts[i] = new Vec2i(Math.round((p[0] - boxMinX) / PIXEL_SIZE),
			                   Math.round((p[1] - boxMinY) / PIXEL_SIZE));
		}
		return pts;
	}

	float[][][] fillSliceGPU(List<List<float[]>> contours, float boxMinX, float boxMinY,
			int width, int height)
	{
		rt.bind();
		GL11.glViewport(0, 0, width, height);

		// Buffer couleur entier : on le vide avec glClearBufferiv, stencil/profondeur avec glClear
		GL30.glClearBufferiv(GL30.GL_COLOR, 0, new int[] { 0, 0, 0, 0 });
		GL11.glClearStencil(0);
		GL11.glStencilMask(0xFF);
		GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

		GL11.glDisable(GL11.GL_CULL_FACE);   // les triangles de l'éventail ont des orientations quelconques
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glEnable(GL11.GL_STENCIL_TEST);

		shader.begin();
		matParam.set(Matrix.createOrtho(0, width, 0, height, -1, 1));

		// Passe 1 : incrémentation du stencil, sans écrire de couleur
		GL11.glColorMask(false, false, false, false);
		GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL14.GL_INCR_WRAP); // wrap : 256 est pair, la parité reste exacte

		for (List<float[]> contour : contours)
		{
			Vec2i[] pts = toPixels(contour, boxMinX, boxMinY);
			for (int i = 1; i <= pts.length - 2; i++)
				new GLVec2iTriangle(new Vec2i[] { pts[0], pts[i], pts[i + 1] }).render();
		}

		// Passe 2 : quad plein écran, uniquement là où le stencil est impair (bit de poids faible = 1)
		GL11.glColorMask(true, true, true, true);
		GL11.glStencilFunc(GL11.GL_EQUAL, 1, 1);
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

		new GLVec2iTriangle(new Vec2i[] { new Vec2i(0, 0), new Vec2i(width, 0), new Vec2i(width, height) }).render();
		new GLVec2iTriangle(new Vec2i[] { new Vec2i(0, 0), new Vec2i(width, height), new Vec2i(0, height) }).render();

		shader.end();
		GL11.glDisable(GL11.GL_STENCIL_TEST);
		rt.unbind();

		return rt.readBackAsFloat();
	}

	/** Pixel de zone impaire = pixel écrit par la passe 2 (canal non nul). */
	static void writePng(float[][][] pixels, File file)
	{
		int h = pixels.length;
		int w = pixels[0].length;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

		for (int y = 0; y < h; y++)
		{
			int row = FLIP_READBACK ? (h - 1 - y) : y;
			for (int x = 0; x < w; x++)
			{
				float[] p = pixels[y][x];
				boolean inside = p[0] != 0 || p[1] != 0 || p[2] != 0;
				img.setRGB(x, row, inside ? 0xFFFFFF : 0x000000);
			}
		}

		try { ImageIO.write(img, "png", file); }
		catch (Exception e) { e.printStackTrace(); }
	}

	// ------------------------------------------------------------------
	// Rasteriseur CPU scanline (TP1), conservé pour comparer avec le GPU
	// ------------------------------------------------------------------

	static class Edge
	{
		int yMin, yMax;     // yMax exclusif
		float xTop, iSlope;

		Edge(float ax, float ay, float bx, float by)
		{
			if (ay > by)
			{
				float t = ax; ax = bx; bx = t;
				t = ay; ay = by; by = t;
			}
			iSlope = (bx - ax) / (by - ay);
			yMin = (int) Math.ceil(ay);
			yMax = (int) Math.ceil(by);
			xTop = ax + (yMin - ay) * iSlope;
		}
	}

	public void rasterEdge(List<Segment> segs, float boxMinX, float boxMaxY,
			int width, int height, File file)
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

		List<Edge> all = new ArrayList<Edge>();
		for (Segment s : segs)
		{
			float ax = (s.a.x - boxMinX) / PIXEL_SIZE, ay = (boxMaxY - s.a.y) / PIXEL_SIZE;
			float bx = (s.b.x - boxMinX) / PIXEL_SIZE, by = (boxMaxY - s.b.y) / PIXEL_SIZE;
			if (ay == by)
				continue;
			Edge e = new Edge(ax, ay, bx, by);
			if (e.yMin < e.yMax)
				all.add(e);
		}
		all.sort(Comparator.comparingInt(e -> e.yMin));

		List<Edge> active = new ArrayList<Edge>();
		int next = 0;
		for (int y = 0; y < height; y++)
		{
			while (next < all.size() && all.get(next).yMin <= y)
			{
				Edge e = all.get(next++);
				e.xTop += (y - e.yMin) * e.iSlope;
				active.add(e);
			}
			for (Iterator<Edge> it = active.iterator(); it.hasNext();)
				if (it.next().yMax <= y)
					it.remove();

			active.sort(Comparator.comparingDouble(e -> e.xTop));
			for (int i = 0; i + 1 < active.size(); i += 2)
				scanLine(image, Math.round(active.get(i).xTop), Math.round(active.get(i + 1).xTop), y);

			for (Edge e : active)
				e.xTop += e.iSlope;
		}

		try { ImageIO.write(image, "png", file); }
		catch (Exception e) { e.printStackTrace(); }
	}

	static void scanLine(BufferedImage image, int x1, int x2, int y)
	{
		int xMin = Math.max(Math.min(x1, x2), 0);
		int xMax = Math.min(Math.max(x1, x2), image.getWidth() - 1);
		for (int x = xMin; x <= xMax; x++)
			image.setRGB(x, y, 0xFFFFFF);
	}
}