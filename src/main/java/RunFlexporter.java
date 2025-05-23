import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

import org.apache.commons.io.FilenameUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ValueSet;
import org.mitre.synthea.export.FhirR4;
import org.mitre.synthea.export.flexporter.Actions;
import org.mitre.synthea.export.flexporter.FhirPathUtils;
import org.mitre.synthea.export.flexporter.FlexporterJavascriptContext;
import org.mitre.synthea.export.flexporter.Mapping;
import org.mitre.synthea.helpers.RandomCodeGenerator;

/**
 * Entrypoint for the "Flexporter" when run as a standalone gradle task.
 * The Flexporter is primarily expected to be used to apply transformations
 * to newly generated Synthea patients, but can also be run against existing FHIR bundles.
 * These bundles are assumed to have been generated by Synthea, but depending on the mapping,
 * they don't necessarily have to be.
 */
public class RunFlexporter {

  /**
   * Main method. Invoke the flexporter with given arguments:
   *  -fm {Flexporter Mapping file path}
   *  -s {Source FHIR file path}
   *  -ig {Implementation Guide file path}
   *
   * @param args Command line args as described above
   */
  public static void main(String[] args) throws Exception {
    Queue<String> argsQ = new ArrayDeque<String>(Arrays.asList(args));

    File igDirectory = null;
    File sourceFile = null;
    File mappingFile = null;

    while (!argsQ.isEmpty()) {
      String currArg = argsQ.poll();

      if (currArg.equals("-ig")) {
        String value = argsQ.poll();

        if (value == null) {
          throw new FileNotFoundException("No implementation guide directory provided");
        }

        igDirectory = new File(value);

        if (!igDirectory.isDirectory()) {
          throw new FileNotFoundException(String.format(
              "Specified implementation guide directory (%s) does not exist or is not a directory",
              value));
        } else if (isDirEmpty(igDirectory.toPath())) {
          throw new FileNotFoundException(
              String.format("Specified implementation guide directory (%s) is empty", value));
        }

      } else if (currArg.equals("-fm")) {
        String value = argsQ.poll();

        if (value == null) {
          throw new FileNotFoundException("No mapping file provided");
        }

        mappingFile = new File(value);

        if (!mappingFile.exists()) {
          throw new FileNotFoundException(
              String.format("Specified mapping file (%s) does not exist", value));
        }

      } else if (currArg.equals("-s")) {
        String value = argsQ.poll();
        sourceFile = new File(value);

        if (value == null) {
          throw new FileNotFoundException("No Synthea source FHIR provided");
        }

        if (!sourceFile.exists()) {
          throw new FileNotFoundException(
              String.format("Specified Synthea source FHIR (%s) does not exist", value));
        }
      }
    }

    if (mappingFile == null || sourceFile == null) {
      usage();
      System.exit(1);
    }

    convertFhir(mappingFile, igDirectory, sourceFile);
  }


  private static Bundle convertFhir(Bundle bundle, Mapping mapping) {
    if (FhirPathUtils.appliesToBundle(bundle, mapping.applicability, mapping.variables)) {
      bundle = Actions.applyMapping(bundle, mapping, null, new FlexporterJavascriptContext());
    }

    return bundle;
  }

  private static void convertFhir(File mappingFile, File igDirectory, File sourceFhir)
      throws IOException {

    Mapping mapping = Mapping.parseMapping(mappingFile);
    mapping.loadValueSets();

    if (igDirectory != null) {
      loadIG(igDirectory);
    }

    IParser parser = FhirR4.getContext().newJsonParser().setPrettyPrint(true);

    handleFile(sourceFhir, mapping, parser);
  }

  private static void handleFile(File sourceFhir, Mapping mapping, IParser parser)
      throws IOException {
    if (sourceFhir.isDirectory()) {
      for (File subfile : sourceFhir.listFiles()) {
        if (subfile.isDirectory() || subfile.getName().endsWith(".json")) {
          handleFile(subfile, mapping, parser);
        }
      }

    } else {
      String fhirJson = new String(Files.readAllBytes(sourceFhir.toPath()));
      Bundle bundle = parser.parseResource(Bundle.class, fhirJson);

      for (BundleEntryComponent bec : bundle.getEntry()) {
        Resource r = bec.getResource();
        if (r.getId().startsWith("urn:uuid:")) {
          // HAPI does some weird stuff with IDs
          // by default in Synthea they are just plain UUIDs
          // and the entry.fullUrl is urn:uuid:(id)
          // but somehow when they get parsed back in, the id is urn:uuid:etc
          // which then doesn't get written back out at the end
          // so this removes the "urn:uuid:" bit if it got added
          r.setId(r.getId().substring(9));
        }
      }

      bundle = convertFhir(bundle, mapping);

      String bundleJson = parser.encodeResourceToString(bundle);

      new File("./output/flexporter/").mkdirs();

      String outFileName = "" + System.currentTimeMillis() + "_" + sourceFhir.getName();

      File outFile =
          new File("./output/flexporter/" + outFileName);

      Files.write(outFile.toPath(), bundleJson.getBytes(Charset.defaultCharset()),
          StandardOpenOption.CREATE_NEW);

      System.out.println("Wrote " + outFile);
    }
  }

  static void loadIG(File igDirectory) throws IOException {
    File[] artifacts = igDirectory.listFiles();

    for (File artifact : artifacts) {
      if (artifact.isFile() && FilenameUtils.getExtension(artifact.toString()).equals("json")) {

        IParser parser = FhirR4.getContext().newJsonParser();

        String fhirJson = new String(Files.readAllBytes(artifact.toPath()));
        IBaseResource resource = null;

        try {
          resource = parser.parseResource(fhirJson);
        } catch (DataFormatException dfe) {
          // why does an IG contain bad data?
          System.err.println("Warning: Unable to parse IG artifact " + artifact.getAbsolutePath());
          dfe.printStackTrace();
        }

        if (resource instanceof ValueSet) {
          try {
            RandomCodeGenerator.loadValueSet(null, (ValueSet)resource);
          } catch (Exception e) {
            System.err.println("WARNING: Unable to load ValueSet " + artifact.getAbsolutePath());
            e.printStackTrace();
          }
        }
      }
    }
  }

  private static boolean isDirEmpty(final Path directory) throws IOException {
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(directory)) {
      return !dirStream.iterator().hasNext();
    }
  }

  private static void usage() {
    System.out.println("Usage: run_flexporter -fm MAPPING_FILE -s SOURCE_FHIR [-ig IG_FOLDER]");
  }
}
