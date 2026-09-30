package fc.PrintingApplication.TP1;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
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
	public static void main(String[] args)
	{
		new Main().parseObjFile("3d_models/CuteOcto.obj");
	}
	
	//
	// Dans ce TP, lire et parser un fichier OBJ
	// puis realiser des coupes par plan Z.
	// Une fois les perimetres extraits:
	// - effectuer une triangulation de Delaunay contrainte.
	// - rasteriser dans une bitmap. Se r�f�rer � l'�nonc� du TP pour tous les d�tails.
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
	        		
	        		triangles.add(new float[] {
						vertex1.v.x, vertex1.v.y, vertex1.v.z,
						vertex2.v.x, vertex2.v.y, vertex2.v.z,
						vertex3.v.x, vertex3.v.y, vertex3.v.z });
	        	}
	        }
			int width  = (int) Math.ceil((boxMax.x - boxMin.x) / PIXEL_SIZE);
			int height = (int) Math.ceil((boxMax.y - boxMin.y) / PIXEL_SIZE);
			int nbSlices = (int) Math.floor((boxMax.z - boxMin.z) / LAYER_HEIGHT + 1e-4f) + 1;
			File outDir = new File("tranches");
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
}
