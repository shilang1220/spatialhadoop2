package edu.umn.cs.spatialHadoop.operations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;

import com.esri.core.geometry.ogc.OGCConcreteGeometryCollection;
import com.esri.core.geometry.ogc.OGCGeometry;
import com.esri.core.geometry.ogc.OGCGeometryCollection;

import edu.umn.cs.spatialHadoop.CommandLineArguments;
import edu.umn.cs.spatialHadoop.core.CellInfo;
import edu.umn.cs.spatialHadoop.core.OGCShape;
import edu.umn.cs.spatialHadoop.core.OSMPolygon;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.mapred.ShapeInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeRecordReader;
import edu.umn.cs.spatialHadoop.mapred.TextOutputFormat;

/**
 * Computes the union of all shapes in a given input file.
 * 
 * @author Ahmed Eldawy
 *
 */
public class Union {
  @SuppressWarnings("unused")
  private static final Log LOG = LogFactory.getLog(Union.class);
  
  static class IdentityMapper extends MapReduceBase
      implements Mapper<Rectangle, OSMPolygon, NullWritable, OSMPolygon> {

    private static final NullWritable dummy = NullWritable.get(); 
    
    @Override
    public void map(Rectangle key, OSMPolygon s,
        OutputCollector<NullWritable, OSMPolygon> output, Reporter reporter)
        throws IOException {
      output.collect(dummy, s);
    }
  }

  /**
   * Reduce function takes a category and union all shapes in that category
   * @author eldawy
   *
   */
  static class UnionReducer extends MapReduceBase
      implements Reducer<NullWritable, OSMPolygon, NullWritable, OSMPolygon> {
    
    @Override
    public void reduce(NullWritable dummy, Iterator<OSMPolygon> shapes,
        OutputCollector<NullWritable, OSMPolygon> output, Reporter reporter)
        throws IOException {
      Vector<OGCGeometry> shapes_list = new Vector<OGCGeometry>();
      while (shapes.hasNext()) {
        OSMPolygon shape = shapes.next();
        shapes_list.add(shape.geom);
      }
      OGCGeometryCollection geo_collection = new OGCConcreteGeometryCollection(
          shapes_list, shapes_list.firstElement().getEsriSpatialReference());
      OGCGeometry union = geo_collection.union(shapes_list.firstElement());
      geo_collection = null;
      shapes = null;
      if (union instanceof OGCGeometryCollection) {
        OGCGeometryCollection union_shapes = (OGCGeometryCollection) union;
        for (int i_geom = 0; i_geom < union_shapes.numGeometries(); i_geom++) {
          OGCGeometry geom_n = union_shapes.geometryN(i_geom);
          output.collect(dummy, new OSMPolygon(geom_n));
        }
      } else {
        output.collect(dummy, new OSMPolygon(union));
      }
    }
  }
  
  /**
   * Calculates the union of a set of shapes.
   * @param shapeFile - Input file that contains shapes
   * @param output - An output file that contains each category and the union
   *  of all shapes in it. Each line contains the category, then a comma,
   *   then the union represented as text.
   * @throws IOException
   */
  public static void unionMapReduce(Path shapeFile,
      Path output, boolean overwrite) throws IOException {
    JobConf job = new JobConf(Union.class);
    job.setJobName("Union");

    // Check output file existence
    FileSystem outFs = output.getFileSystem(job);
    if (outFs.exists(output)) {
      if (overwrite) {
        outFs.delete(output, true);
      } else {
        throw new RuntimeException("Output path already exists and -overwrite flag is not set");
      }
    }
    
    // Set map and reduce
    ClusterStatus clusterStatus = new JobClient(job).getClusterStatus();
    job.setNumMapTasks(clusterStatus.getMaxMapTasks() * 5);
    job.setNumReduceTasks(Math.max(1, clusterStatus.getMaxReduceTasks() * 9 / 10));
    
    job.setMapperClass(IdentityMapper.class);
    job.setCombinerClass(UnionReducer.class);
    job.setReducerClass(UnionReducer.class);
    
    job.setMapOutputKeyClass(NullWritable.class);
    job.setMapOutputValueClass(OSMPolygon.class);

    // Set input and output
    job.setInputFormat(ShapeInputFormat.class);
    SpatialSite.setShapeClass(job, OSMPolygon.class);
    TextInputFormat.addInputPath(job, shapeFile);
    
    job.setOutputFormat(TextOutputFormat.class);
    TextOutputFormat.setOutputPath(job, output);

    // Start job
    JobClient.runJob(job);
    
    // TODO If outputPath not set by user, automatically delete it
//    if (userOutputPath == null)
//      outFs.delete(outputPath, true);
  }
  
  
  /**
   * Convert a string containing a hex string to a byte array of binary.
   * For example, the string "AABB" is converted to the byte array {0xAA, 0XBB}
   * @param hex
   * @return
   */
  public static byte[] hexToBytes(String hex) {
    byte[] bytes = new byte[(hex.length() + 1) / 2];
    for (int i = 0; i < hex.length(); i++) {
      byte x = (byte) hex.charAt(i);
      if (x >= '0' && x <= '9')
        x -= '0';
      else if (x >= 'a' && x <= 'f')
        x = (byte) ((x - 'a') + 0xa);
      else if (x >= 'A' && x <= 'F')
        x = (byte) ((x - 'A') + 0xA);
      else
        throw new RuntimeException("Invalid hex char "+x);
      if (i % 2 == 0)
        x <<= 4;
      bytes[i / 2] |= x;
    }
    return bytes;
  }

  
  public static <S extends OGCShape> OGCGeometry unionStream(S shape) throws IOException {
    ShapeRecordReader<S> reader =
        new ShapeRecordReader<S>(System.in, 0, Long.MAX_VALUE);
    final int threshold = 5000000;
    ArrayList<OGCGeometry> polygons = new ArrayList<OGCGeometry>();
    
    Rectangle key = new Rectangle();

    while (reader.next(key, shape)) {
      polygons.add(shape.geom);
      if (polygons.size() >= threshold) {
        OGCGeometryCollection collection = new OGCConcreteGeometryCollection(polygons, polygons.get(0).esriSR);
        OGCGeometry union = collection.union(polygons.get(0));
        polygons.clear();
        polygons.add(union);
        System.err.println("Size: "+polygons.size());
      }
    }
    OGCGeometryCollection collection = new OGCConcreteGeometryCollection(polygons, polygons.get(0).esriSR);
    OGCGeometry union = collection.union(polygons.get(0));
    polygons.clear();
    return union;
  }

  /**
   * Calculates the union of a set of shapes
   * @param fs
   * @param file
   * @return
   * @throws IOException
   */
  public static OGCGeometry unionLocal(Path shapeFile)
      throws IOException {
    // Read shapes from the shape file and relate each one to a category
    
    // Prepare a hash that stores all shapes
    Vector<OGCGeometry> shapes = new Vector<OGCGeometry>();
    
    FileSystem fs1 = shapeFile.getFileSystem(new Configuration());
    long file_size1 = fs1.getFileStatus(shapeFile).getLen();
    
    ShapeRecordReader<OSMPolygon> shapeReader =
        new ShapeRecordReader<OSMPolygon>(fs1.open(shapeFile), 0, file_size1);
    CellInfo cellInfo = new CellInfo();
    OSMPolygon shape = new OSMPolygon();

    while (shapeReader.next(cellInfo, shape)) {
      shapes.add(shape.geom);
    }
    shapeReader.close();

    // Find the union of all shapes
    OGCGeometryCollection all_geoms = new OGCConcreteGeometryCollection(shapes,
        shapes.firstElement().getEsriSpatialReference());

    OGCGeometry union = all_geoms.union(shapes.firstElement());
    
    return union;
  }

  private static void printUsage() {
    System.out.println("Finds the union of all shapes in the input file.");
    System.out.println("The output is one shape that represents the union of all shapes in input file.");
    System.out.println("Parameters: (* marks required parameters)");
    System.out.println("<shape file>: (*) Path to file that contains all shapes");
    System.out.println("<output file>: (*) Path to output file.");
  }

  /**
   * @param args
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    CommandLineArguments cla = new CommandLineArguments(args);
    JobConf conf = new JobConf(Union.class);
    Path[] allFiles = cla.getPaths();
    boolean local = cla.isLocal();
    boolean overwrite = cla.isOverwrite();
    if (allFiles.length == 0) {
      if (local) {
        long t1 = System.currentTimeMillis();
        unionStream((OGCShape)cla.getShape(true));
        long t2 = System.currentTimeMillis();
        System.err.println("Total time for union: "+(t2-t1)+" millis");
        return;
      }
      printUsage();
      throw new RuntimeException("Illegal arguments. Input file missing");
    }
    
    for (int i = 0; i < allFiles.length - 1; i++) {
      Path inputFile = allFiles[i];
      FileSystem fs = inputFile.getFileSystem(conf);
      if (!fs.exists(inputFile)) {
        printUsage();
        throw new RuntimeException("Input file does not exist");
      }
    }

    long t1 = System.currentTimeMillis();
    if (local) {
      unionLocal(allFiles[0]);
    } else {
      unionMapReduce(allFiles[0], allFiles[1], overwrite);
    }
    long t2 = System.currentTimeMillis();
    System.out.println("Total time: "+(t2-t1)+" millis");
  }

}
