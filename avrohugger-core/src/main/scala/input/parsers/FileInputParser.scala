package avrohugger
package input
package parsers

import format.abstractions.SourceFormat
import stores.ClassStore

import org.apache.avro.{ Protocol, Schema }
import org.apache.avro.Schema.Parser
import org.apache.avro.Schema.Type.{ RECORD, UNION, ENUM }
import org.apache.avro.compiler.idl.Idl
import org.apache.avro.generic.{ GenericDatumReader, GenericRecord }
import org.apache.avro.file.DataFileReader

import java.io.File

import scala.collection.JavaConversions._

class FileInputParser {
  
  val schemaParser = new Parser()

  def getSchemaOrProtocols(
    infile: File,
    format: SourceFormat,
    classStore: ClassStore,
    parser: Parser = schemaParser): List[Either[Schema, Protocol]] = {
    
    def unUnion(schema: Schema) = {
      schema.getType match {
        //if top-level record is wrapped in a union with no other types
        case UNION => {
          val types = schema.getTypes.toList
          if (types.length == 1) types.head
          else sys.error("""Unions, beyond nullable fields, are not supported. 
            |Found a union of more than one type: """.trim.stripMargin + types)
        }
        case RECORD => schema
        case ENUM => schema
        case _ => sys.error("""Neither a record, enum nor a union of either. 
          |Nothing to map to a definition.""".trim.stripMargin)
      }
    }
    
    
    val schemaOrProtocols: List[Either[Schema, Protocol]] = {
      infile.getName.split("\\.").last match {
        case "avro" =>
          val gdr = new GenericDatumReader[GenericRecord]
          val dfr = new DataFileReader(infile, gdr)
          val schema = unUnion(dfr.getSchema)
          List(Left(schema))
        case "avsc" =>
          val schema = unUnion(parser.parse(infile))
          List(Left(schema))
        case "avpr" =>
          val protocol = Protocol.parse(infile)
          List(Right(protocol))
        case "avdl" =>
          val idlParser = new Idl(infile)
          val protocol = idlParser.CompilationUnit()
          /**
           * IDLs may refer to types imported from another file. When converted 
           * to protocols, the imported types that share the IDL's namespace 
           * cannot be distinguished from types defined within the IDL, yet 
           * should not be generated as subtypes of the IDL's ADT and should 
           * instead be generated in its own namespace. So, strip the protocol 
           * of all imported types and generate them separately.
           */
          val importedFiles = IdlImportParser.getImportedFiles(infile)
          val importedSchemaOrProtocols = importedFiles.flatMap(file => {
            val importParser = new Parser() // else attempts to redefine schemas
            getSchemaOrProtocols(file, format, classStore, importParser)
          }).toList
          def stripImports(
            protocol: Protocol,
            importedSchemaOrProtocols: List[Either[Schema, Protocol]]) = {
            val imported = importedSchemaOrProtocols.flatMap(avroDef => {
              avroDef match {
                case Left(importedSchema) => List(importedSchema)
                case Right(importedProtocol) => importedProtocol.getTypes.toList
              }
            })
            val types = protocol.getTypes.toList
            val localTypes = imported.foldLeft(types)((remaining, imported) => {
              remaining.filterNot(remainingType => remainingType == imported)
            })
            protocol.setTypes(localTypes)
            protocol
          }
          val localProtocol = stripImports(protocol, importedSchemaOrProtocols)
          // reverse to dependent classes are generated first
          (Right(localProtocol) +: importedSchemaOrProtocols).reverse
        case _ =>
          throw new Exception("""File must end in ".avpr" for protocol files, 
            |".avsc" for plain text json files, ".avdl" for IDL files, or .avro 
            |for binary.""".trim.stripMargin)
      }
    }
    
    schemaOrProtocols
  }
}