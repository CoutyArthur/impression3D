package fc.PrintingApplication.TP1;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;

import com.owens.oobjloader.builder.Build;
import com.owens.oobjloader.builder.Face;
import com.owens.oobjloader.builder.FaceVertex;
import com.owens.oobjloader.parser.Parse;

import fc.Math.AABB;
import fc.Math.Vec3f;

public class Main
{
	static final float LAYER_HEIGHT = 0.20f;  // pas vertical (mm)
	static final float PIXEL_SIZE   = 0.05f;  // 1 pixel = 0,05 mm
	static final float MARGIN_XY    = 10f;    // 1 cm de chaque côté en X et Y (mm)

	// true : tranches pleines (remplissage scanline), false : contours seuls
	static final boolean PLEIN = true;


	// Segment de tranche : tous les sommets partagent la même cote Z.
	static class Segment
	{
		final Vec3f a, b;
		Segment(float ax, float ay, float bx, float by, float z)
		{
			this.a = new Vec3f(ax, ay, z);
			this.b = new Vec3f(bx, by, z);
		}
	}

	// Arête pour le remplissage scanline. Coordonnées DÉJÀ en pixels.
	// Doit être appelée uniquement avec ay != by.
	static class Edge
	{
		int yMin;      // première ligne de scan coupée (inclusive)
		int yMax;      // dernière ligne de scan coupée (exclusive)
		float xTop;    // x de l'intersection à la ligne yMin
		float iSlope;  // dx / dy

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

		// Conversion mm -> pixels (même convention que renderSlice : Y image vers le bas)
		List<Edge> all = new ArrayList<Edge>();
		for (Segment s : segs)
		{
			float ax = (s.a.x - boxMinX) / PIXEL_SIZE;
			float ay = (boxMaxY - s.a.y) / PIXEL_SIZE;
			float bx = (s.b.x - boxMinX) / PIXEL_SIZE;
			float by = (boxMaxY - s.b.y) / PIXEL_SIZE;

			if (ay == by)
				continue; // horizontale : ne contribue pas au remplissage

			Edge e = new Edge(ax, ay, bx, by);
			if (e.yMin < e.yMax) // sinon l'arête ne croise aucune ligne de scan
				all.add(e);
		}
		all.sort(Comparator.comparingInt(e -> e.yMin));

		List<Edge> active = new ArrayList<Edge>();
		int next = 0;

		for (int y = 0; y < height; y++)
		{
			// Activation des arêtes qui commencent à cette ligne
			while (next < all.size() && all.get(next).yMin <= y)
			{
				Edge e = all.get(next++);
				e.xTop += (y - e.yMin) * e.iSlope; // rattrape si yMin < 0
				active.add(e);
			}

			// Retrait des arêtes terminées
			for (Iterator<Edge> it = active.iterator(); it.hasNext();)
				if (it.next().yMax <= y)
					it.remove();

			// Remplissage pair/impair entre les intersections triées en x
			active.sort(Comparator.comparingDouble(e -> e.xTop));
			for (int i = 0; i + 1 < active.size(); i += 2)
				scanLine(image, Math.round(active.get(i).xTop),
						Math.round(active.get(i + 1).xTop), y);

			// Incrément incrémental de x pour la ligne suivante
			for (Edge e : active)
				e.xTop += e.iSlope;
		}

		// Toujours écrire le PNG, même pour une tranche vide
		try { ImageIO.write(image, "png", file); }
		catch (Exception e) { e.printStackTrace(); }
	}

	static public void scanLine(BufferedImage image, int x1, int x2, int y)
	{
		int xMin = Math.max(Math.min(x1, x2), 0);
		int xMax = Math.min(Math.max(x1, x2), image.getWidth() - 1);

		for (int x = xMin; x <= xMax; x++)
			image.setRGB(x, y, 0xFFFFFF);
	}

	public static void main(String[] args)
	{
		new Main().parseObjFile("3d_models/CuteOcto.obj");
	}

	//
	// Dans ce TP, lire et parser un fichier OBJ
	// puis réaliser des coupes par plan Z.
	// Une fois les périmètres extraits:
	// - effectuer une triangulation de Delaunay contrainte.
	// - rasteriser dans une bitmap. Se référer à l'énoncé du TP pour tous les détails.
	//
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

				aa.enlarge(new Vec3f(x, y, z));
			}

			aa.addMargin(new Vec3f(MARGIN_XY, MARGIN_XY, 0));

			Vec3f boxMin = aa.getMin();
			Vec3f boxMax = aa.getMax();

			// Enumeration des faces (souvent des triangles, mais peuvent comporter plus de sommets dans certains cas)

			for (Face face : builder.faces)
			{
				// Parcours des triangles de cette face

				for (int i = 1; i <= (face.vertices.size() - 2); i++)
				{
					int vertexIndex1 = face.vertices.get(0).index;
					int vertexIndex2 = face.vertices.get(i).index;
					int vertexIndex3 = face.vertices.get(i + 1).index;

					FaceVertex vertex1 = builder.faceVerticeList.get(vertexIndex1);
					FaceVertex vertex2 = builder.faceVerticeList.get(vertexIndex2);
					FaceVertex vertex3 = builder.faceVerticeList.get(vertexIndex3);

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

			File outDir = new File(PLEIN ? "tranchesPleine" : "tranchesCreuse");
			outDir.mkdirs();

			// nbSlices contient déjà le "+1" (tranche à z = boxMin.z incluse)
			for (int k = 0; k < nbSlices; k++)
			{
				float zCut = boxMin.z + k * LAYER_HEIGHT;
				List<Segment> segments = sliceAtZ(triangles, zCut);
				File out = new File(outDir, String.format("tranche_%04d.png", k));

				if (PLEIN)
					rasterEdge(segments, boxMin.x, boxMax.y, width, height, out);
				else
					renderSlice(segments, boxMin.x, boxMax.y, width, height, out);
			}
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
}