package uk.ac.ebi.ena.sra.cram;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import net.sf.picard.io.IoUtil;
import net.sf.picard.sam.AlignmentSliceQuery;
import net.sf.samtools.CRAMFileReader;
import net.sf.samtools.FileInputStreamFactory;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMFileHeader.SortOrder;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileWriter;
import net.sf.samtools.SAMFileWriterFactory;
import net.sf.samtools.SAMProgramRecord;
import net.sf.samtools.SAMReadGroupRecord;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordIterator;
import net.sf.samtools.SAMSequenceRecord;
import net.sf.samtools.SingleSeekableStreamFactory;
import uk.ac.ebi.ena.sra.cram.spot.PairedTemplateAssembler;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.FileConverter;

public class SliceView {

	public static void usage(JCommander jc) {
		StringBuilder sb = new StringBuilder();
		sb.append("\n");
		jc.usage(sb);

		System.out.println("Version " + SliceView.class.getPackage().getImplementationVersion());
		System.out.println(sb.toString());
	}

	public static void main(String[] args) {
		Params params = new Params();
		JCommander jc = new JCommander(params);
		jc.parse(args);

		if (args.length == 0 || params.help) {
			usage(jc);
			System.exit(1);
		}

		if (params.reference == null) {
			System.out.println("A reference fasta file is required.");
			System.exit(1);
		}

		if (params.files == null || params.files.isEmpty()) {
			System.out.println("At least one CRAM or BAM file is required.");
			System.exit(1);
		}

		AlignmentSliceQuery query = new AlignmentSliceQuery(params.region);

		List<SAMFileReader> readers = new ArrayList<SAMFileReader>(params.files.size());
		StringBuffer mergeComment = new StringBuffer("Merged from:");
		for (File file : params.files) {
			IoUtil.assertFileIsReadable(file);
			File index = new File(file.getAbsolutePath() + ".bai");
			if (!index.exists())
				index = new File(file.getAbsolutePath() + ".crai");
			if (!index.exists())
				index = null;
			
			if (params.reference != null) System.setProperty("reference", params.reference.getAbsolutePath()) ;
			SAMFileReader reader = new SAMFileReader(file, index);

			// reader = new CRAMFileReader(new SingleSeekableStreamFactory(new
			// BufferedInputStream(new FileInputStream(
			// file))), new FileInputStreamFactory(file), params.reference,
			// index);

			readers.add(reader);

			mergeComment.append(" ").append(file.getAbsolutePath());
		}

		SAMFileHeader header = mergeHeaders(readers);
		header.addComment(mergeComment.toString());

		SAMFileWriter writer = null;
		if (params.outFile != null)
			if (!params.samFormat)
				writer = new SAMFileWriterFactory().makeBAMWriter(header, true, params.outFile);
			else
				writer = new SAMFileWriterFactory().makeSAMWriter(header, true, params.outFile);
		else if (!params.samFormat)
			throw new RuntimeException("Streaming out BAM format is not supported.");
		else
			writer = new SAMFileWriterFactory().makeSAMWriter(header, true, new BufferedOutputStream(System.out));

		List<SAMRecordIterator> iterators = new ArrayList<SAMRecordIterator>(readers.size());
		for (SAMFileReader reader : readers) {
			SAMRecordIterator it = reader.query(query.sequence, query.start, query.end, false);
			iterators.add(it);
		}

		MergedSAMRecordIterator mergedIterator = new MergedSAMRecordIterator(iterators, header);
		while (mergedIterator.hasNext()) {
			writer.addAlignment(mergedIterator.next());
		}

		mergedIterator.close();
		for (SAMFileReader reader : readers)
			reader.close();

		writer.close();
	}

	private static class MergedSAMRecordIterator implements SAMRecordIterator {

		private List<SAMRecordIterator> iterators;
		private PairedTemplateAssembler assembler = new PairedTemplateAssembler(Integer.MAX_VALUE, Integer.MAX_VALUE);
		private SAMRecord nextRecord;
		private SAMFileHeader header;

		public MergedSAMRecordIterator(List<SAMRecordIterator> iterators, SAMFileHeader header) {
			this.iterators = iterators;
			this.header = header;
			nextRecord = doNext();
		}

		@Override
		public void close() {
			for (SAMRecordIterator it : iterators)
				it.close();
		}

		@Override
		public boolean hasNext() {
			return nextRecord != null;
		}

		private boolean milk() {
			boolean hasMore = false;
			int counter = 0;
			for (SAMRecordIterator it : iterators) {
				counter++;
				if (it.hasNext()) {
					SAMRecord record = it.next();
					record.setReadName(String.valueOf(counter) + "." + record.getReadName());
					assembler.addSAMRecordNoAssembly(record);
					hasMore = true;
				}
			}
			return hasMore;
		}

		private SAMRecord doNext() {
			SAMRecord nextRecord = null;
			do {
				nextRecord = assembler.nextSAMRecord();
			} while (nextRecord == null && milk());

			if (nextRecord == null)
				nextRecord = assembler.fetchNextSAMRecord();

			if (nextRecord != null) {
				SAMSequenceRecord sequence = header.getSequence(nextRecord.getReferenceName());
				nextRecord.setReferenceIndex(sequence.getSequenceIndex());
			}

			return nextRecord;
		}

		@Override
		public SAMRecord next() {
			if (nextRecord == null)
				throw new RuntimeException("Iterator exchausted.");

			SAMRecord toReturn = nextRecord;
			nextRecord = doNext();

			return toReturn;
		}

		@Override
		public void remove() {
			throw new RuntimeException("Not implemented.");
		}

		@Override
		public SAMRecordIterator assertSorted(SortOrder sortOrder) {
			if (sortOrder != SortOrder.coordinate)
				throw new RuntimeException("Only coordinate sort order is supported: " + sortOrder.name());

			return null;
		}

	}

	private static SAMFileHeader mergeHeaders(List<SAMFileReader> readers) {
		SAMFileHeader header = new SAMFileHeader();
		for (SAMFileReader reader : readers) {
			SAMFileHeader h = reader.getFileHeader();

			for (SAMSequenceRecord seq : h.getSequenceDictionary().getSequences()) {
				if (header.getSequenceDictionary().getSequence(seq.getSequenceName()) == null)
					header.addSequence(seq);
			}

			for (SAMProgramRecord pro : h.getProgramRecords()) {
				if (h.getProgramRecord(pro.getProgramGroupId()) == null)
					header.addProgramRecord(pro);
			}

			for (String comment : h.getComments())
				header.addComment(comment);

			for (SAMReadGroupRecord rg : h.getReadGroups()) {
				if (h.getReadGroup(rg.getReadGroupId()) == null)
					header.addReadGroup(rg);
			}

		}
		return header;
	}

	@Parameters(commandDescription = "CRAM to BAM conversion. ")
	static class Params {

		@Parameter(names = { "--reference-fasta-file" }, converter = FileConverter.class, description = "Path to the reference fasta file, it must be uncompressed and indexed (use 'samtools faidx' for example).")
		File reference;

		@Parameter(names = { "--output-file" }, converter = FileConverter.class, description = "Path to the output BAM file. Omit for stdout.")
		File outFile;

		@Parameter(names = { "--sam-format" }, description = "Output in SAM rather than BAM format.")
		boolean samFormat = false;

		@Parameter(names = { "--region", "-r" }, description = "Alignment slice specification, for example: chr1:65000-100000.")
		String region;

		@Parameter(names = { "-h", "--help" }, description = "Print help and quit")
		boolean help = false;

		@Parameter(converter = FileConverter.class, description = "The paths to the CRAM or BAM files to uncompress. ")
		List<File> files;

	}
}