package uk.ac.ebi.ena.sra.cram;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import net.sf.picard.reference.ReferenceSequence;
import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.samtools.AlignmentBlock;
import net.sf.samtools.Cigar;
import net.sf.samtools.CigarElement;
import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileReader.ValidationStringency;
import net.sf.samtools.SAMReadGroupRecord;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordIterator;
import net.sf.samtools.SAMSequenceRecord;

import org.apache.commons.io.output.NullOutputStream;
import org.apache.log4j.Logger;

import uk.ac.ebi.ena.sra.cram.bam.Sam2CramRecordFactory;
import uk.ac.ebi.ena.sra.cram.bam.Sam2CramRecordFactory.TREAT_TYPE;
import uk.ac.ebi.ena.sra.cram.format.CramReadGroup;
import uk.ac.ebi.ena.sra.cram.format.CramRecord;
import uk.ac.ebi.ena.sra.cram.format.CramReferenceSequence;
import uk.ac.ebi.ena.sra.cram.format.text.CramRecordFormat;
import uk.ac.ebi.ena.sra.cram.impl.CramWriter;
import uk.ac.ebi.ena.sra.cram.impl.LocalReorderingSAMRecordQueue;
import uk.ac.ebi.ena.sra.cram.impl.ReadAnnotationReader;
import uk.ac.ebi.ena.sra.cram.mask.FastaByteArrayMaskFactory;
import uk.ac.ebi.ena.sra.cram.mask.IntegerListMaskFactory;
import uk.ac.ebi.ena.sra.cram.mask.ReadMaskFactory;
import uk.ac.ebi.ena.sra.cram.mask.RefMaskUtils;
import uk.ac.ebi.ena.sra.cram.mask.SingleLineMaskReader;
import uk.ac.ebi.ena.sra.cram.spot.PairedTemplateAssembler;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.FileConverter;

public class Bam2Cram {

	private static Logger log = Logger.getLogger(Bam2Cram.class);

	private SAMFileReader samReader;
	private CramWriter cramWriter;
	private PairedTemplateAssembler assembler;
	private Sam2CramRecordFactory cramRecordFactory;
	private List<CramReferenceSequence> sequences;
	private OutputStream os;
	private SequenceBaseProvider provider;
	private SingleLineMaskReader maskReader;
	private ReadAnnotationReader readAnnoReader;
	private PrintStream statsPS;
	private PrintStream tramPS;

	private ReferenceSequenceFile referenceSequenceFile;
	private CramRecordFormat cramRecordFormat = new CramRecordFormat();

	private long recordCount = 0;
	private long unmappedRecordCount = 0;
	private long baseCount = 0;
	private long landedRefMaskScores = 0;
	private long landedPiledScores = 0;
	private long landedTotalScores = 0;

	private Map<String, Integer> readGroupIdToIndexMap = new TreeMap<String, Integer>();
	private LocalReorderingSAMRecordQueue reoderingQueue = new LocalReorderingSAMRecordQueue(10000);

	private Params params;

	public Bam2Cram(Params params) {
		this.params = params;
	}

	public void init() throws IOException, CramException {
		log.info("Input BAM file: " + params.bamFile.getAbsolutePath());

		samReader = new SAMFileReader(params.bamFile);
		samReader.setValidationStringency(ValidationStringency.SILENT);
		sequences = new ArrayList<CramReferenceSequence>();
		for (SAMSequenceRecord seq : samReader.getFileHeader().getSequenceDictionary().getSequences()) {
			if (params.sequences != null && !params.sequences.isEmpty()
					&& !params.sequences.contains(seq.getSequenceName()))
				continue;
			CramReferenceSequence cramSeq = new CramReferenceSequence(seq.getSequenceName(), seq.getSequenceLength());
			sequences.add(cramSeq);
		}

		List<CramReadGroup> cramReadGroups = new ArrayList<CramReadGroup>(samReader.getFileHeader().getReadGroups()
				.size() + 1);
		cramReadGroups.add(new CramReadGroup(null));
		for (SAMReadGroupRecord rgr : samReader.getFileHeader().getReadGroups()) {
			readGroupIdToIndexMap.put(rgr.getReadGroupId(), readGroupIdToIndexMap.size() + 1);
			cramReadGroups.add(new CramReadGroup(rgr.getReadGroupId(), rgr.getSample()));
		}

		referenceSequenceFile = Utils.createIndexedFastaSequenceFile(params.referenceFasta);
		assembler = new PairedTemplateAssembler(params.spotAssemblyAlignmentHorizon, params.spotAssemblyRecordsHorizon);

		if (params.readQualityMaskFile != null) {
			log.info("Using read quality mask file: " + params.readQualityMaskFile);
			ReadMaskFactory<String> rqmFactory = params.fastaReadQualityMasking ? new FastaByteArrayMaskFactory()
					: new IntegerListMaskFactory();
			maskReader = new SingleLineMaskReader(new BufferedReader(new FileReader(params.readQualityMaskFile)),
					rqmFactory);
		}

		if (params.readAnnoFile != null) {
			readAnnoReader = new ReadAnnotationReader(new BufferedReader(new FileReader(params.readAnnoFile)));
		}

		recordCount = 0;
		unmappedRecordCount = 0;
		baseCount = 0;

		if (params.outputCramFile != null)
			log.info("Output CRAM file: " + params.outputCramFile.getAbsolutePath());
		else
			log.info("No output CRAM file specified, discarding CRAM output.");

		os = createOutputStream(params.outputCramFile, false);

		statsPS = params.statsOutFile == null ? null : new PrintStream(params.statsOutFile);

		tramPS = params.tramOutFile == null ? null : new PrintStream(params.tramOutFile);

		cramWriter = new CramWriter(os, provider, sequences, params.roundTripCheck, params.maxBlockSize,
				params.captureUnmappedQualityScore, params.captureSubstitutionQualityScore,
				params.captureMaskedQualityScore, readAnnoReader == null ? null : readAnnoReader.listUniqAnnotations(),
				statsPS, cramReadGroups);
		cramWriter.setAutodump(log.isInfoEnabled());
		cramWriter.init();
	}

	public void close() throws IOException {
		os.close();
		if (statsPS != null)
			statsPS.close();
		if (tramPS != null)
			tramPS.close();

		if (params.outputCramFile != null)
			log.info(String.format("Total compression: %.2f", 8f * params.outputCramFile.length() / baseCount));
	}

	public void run() throws IOException, CramException {
		for (CramReferenceSequence ref : sequences) {
			if (recordCount >= params.maxRecords)
				break;
			compressAllRecordsForSequence2(ref.getName());
		}
		cramWriter.close();

		log.info(String.format("Found SAM records: %d\tunmapped: %d", recordCount, unmappedRecordCount));
		log.info(String.format("Compressed bases: %d", baseCount));
		log.info(String.format("Landed ref masked qscores: %d", landedRefMaskScores));
		log.info(String.format("Landed piled qscores: %d", landedPiledScores));
		log.info(String.format("Landed total qscores: %d", landedTotalScores));
		log.info(String.format("Quality budget: %.2f%%", baseCount == 0 ? 0 : 100f * landedTotalScores / baseCount));
	}

	private byte[] getReferenceSequenceBases(String seqName) {
		ReferenceSequence sequence = referenceSequenceFile.getSequence(seqName);
		byte[] refBases = referenceSequenceFile.getSubsequenceAt(sequence.getName(), 1, sequence.length()).getBases();
		Utils.capitaliseAndCheckBases(refBases, false);
		return refBases;
	}

	private static byte[] refPos2RefSNPs(File file, int refLen, byte onValue) throws IOException {
		if (file == null)
			return null;
		byte[] refSNPs = new byte[refLen];
		BufferedReader r = new BufferedReader(new FileReader(file));
		String line = null;
		while ((line = r.readLine()) != null) {
			int pos = Integer.valueOf(line);
			refSNPs[pos] = onValue;
		}
		r.close();
		return refSNPs;
	}

	private void compressAllRecordsForSequence2(String name) throws IOException, CramException {
		assembler.clear();
		cramRecordFormat.setSequenceID(name);

		SAMRecordIterator iterator = samReader.query(name, 0, 0, false);

		long recordsInSequence = 0L;
		try {
			if (!iterator.hasNext())
				return;
			byte[] refBases = getReferenceSequenceBases(name);
			byte[] refSNPs = refPos2RefSNPs(params.refSnpPosFile, refBases.length, (byte) '+');
			RefMaskUtils.RefMask refPileMasks = null;
			if (params.capturePiledQualityScores) {
				refPileMasks = new RefMaskUtils.RefMask(refBases.length, params.minPiledHits);
			}

			cramRecordFactory = new Sam2CramRecordFactory(refBases, refSNPs, refPileMasks, readGroupIdToIndexMap);
			cramRecordFactory.setCaptureInsertScores(params.captureInsertionQualityScore);
			cramRecordFactory.setCaptureSubtitutionScores(params.captureSubstitutionQualityScore);
			cramRecordFactory.setCaptureUnmappedScores(params.captureUnmappedQualityScore);
			cramRecordFactory.setUncategorisedQualityScoreCutoff(params.qualityCutoff);

			if (params.ignoreSoftClips)
				cramRecordFactory.setTreatSoftClipsAs(TREAT_TYPE.IGNORE);
			else
				cramRecordFactory.setTreatSoftClipsAs(TREAT_TYPE.INSERTION);

			cramWriter.startSequence(name, refBases);
			LinkedList<SAMRecord> recordBuffer = new LinkedList<SAMRecord>();
			SAMRecord tempRecord = null;
			int alEnd = 0;
			while (iterator.hasNext() && recordCount++ < params.maxRecords
					&& recordsInSequence++ < params.maxRecordsPerSequence) {
				SAMRecord samRecord = iterator.next();
				recordBuffer.add(samRecord);

				if (refPileMasks != null)
					addRefMask(samRecord, refBases, refPileMasks);

				while (!recordBuffer.isEmpty()) {
					tempRecord = recordBuffer.peekFirst();
					if (tempRecord.getReadUnmappedFlag())
						alEnd = tempRecord.getAlignmentStart() + tempRecord.getReadLength();
					else
						alEnd = tempRecord.getAlignmentEnd();

					if (alEnd < samRecord.getAlignmentStart()) {
						compressRecord(recordBuffer.pollFirst());
					} else
						break;
				}
			}
			while (!recordBuffer.isEmpty())
				compressRecord(recordBuffer.pollFirst());

			flushSpotAssembler();
			assembler.clear();

			landedPiledScores += cramRecordFactory.getLandedPiledScores();
			landedRefMaskScores += cramRecordFactory.getLandedRefMaskScores();
			landedTotalScores += cramRecordFactory.getLandedTotalScores();
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			iterator.close();
		}
	}

	private void compressRecord(SAMRecord samRecord) throws IOException, CramException {
		// if (params.qualityCutoff > 0) {
		// byte[] originalScores = new
		// byte[samRecord.getBaseQualities().length];
		// System.arraycopy(samRecord.getBaseQualities(), 0, originalScores, 0,
		// originalScores.length);
		// for (int i = 0; i < originalScores.length; i++) {
		// if (originalScores[i] < params.qualityCutoff)
		// samRecord.getBaseQualities()[i] = originalScores[i];
		// else
		// samRecord.getBaseQualities()[i] =
		// Sam2CramRecordFactory.ignorePositionsWithQualityScore;
		// }
		// }

		addSAMRecord(samRecord);
	}

	private void addRefMask(SAMRecord record, byte[] refBases, RefMaskUtils.RefMask refMask) {
		int refStartInBlock;
		int readStartInBlock;
		int refStart;
		int readStart;
		byte readBase;
		byte refBase;
		for (AlignmentBlock block : record.getAlignmentBlocks()) {
			refStartInBlock = block.getReferenceStart();
			readStartInBlock = block.getReadStart();
			byte[] readBases = record.getReadBases();
			for (int i = 0; i < block.getLength(); i++) {
				refStart = refStartInBlock + i - 1;
				readStart = readStartInBlock + i - 1;
				readBase = readBases[readStart];
				refBase = refBases[refStart];
				refMask.addReadBase(refStart, readBase, refBase);
			}
		}
	}

	private void flushSpotAssembler() throws IOException, CramException {
		SAMRecord assembledRecord = null;
		while ((assembledRecord = assembler.fetchNextSAMRecord()) != null) {
			writeSAMRecord(assembledRecord, assembler.distanceToNextFragment());
		}
	}

	private static final void rewriteFirstClipIntoInsertion(SAMRecord record) {
		if (record.getAlignmentStart() == record.getUnclippedStart())
			return;

		Cigar cigar = record.getCigar();
		int clipLength = 0;
		LinkedList<CigarElement> newElements = new LinkedList<CigarElement>();
		newElements.addAll(cigar.getCigarElements());

		while (newElements.get(0).getOperator() == CigarOperator.S
				|| newElements.get(0).getOperator() == CigarOperator.H) {
			clipLength += newElements.remove(0).getLength();
		}

		// int elementsToRemove = 0;
		// for (CigarElement ce : cigarElements) {
		// if (ce.getOperator() == CigarOperator.S
		// || ce.getOperator() == CigarOperator.H) {
		// clipLength += ce.getLength();
		// elementsToRemove++;
		// } else
		// break;
		// }

		if (newElements.size() > 1 && newElements.get(1).getOperator() == CigarOperator.I) {
			newElements.removeFirst();

			int newInsertLength = clipLength + newElements.getFirst().getLength();
			newElements.set(0, new CigarElement(newInsertLength, CigarOperator.I));
		} else {
			newElements.addFirst(new CigarElement(clipLength, CigarOperator.M));
		}

		record.setAlignmentStart(record.getUnclippedStart());
		record.setCigar(new Cigar(newElements));
	}

	private void addSAMRecord(SAMRecord samRecord) throws IOException, CramException {
		assembler.addSAMRecord(samRecord);
		SAMRecord assembledRecord = null;
		while ((assembledRecord = assembler.nextSAMRecord()) != null) {
			writeSAMRecord(assembledRecord, assembler.distanceToNextFragment());
		}
	}

	private static final void treatQScores(byte[] scores) {
		for (int i = 0; i < scores.length; i++)
			if (scores[i] == -1)
				scores[i] = '?' - 33;
	}

	private void writeSAMRecord(SAMRecord record, int distanceToNextFragment) throws IOException, CramException {
		CramRecord cramRecord = buildCramRecord(record);
		if (record.getReadGroup() != null) {
			String readGroupId = record.getReadGroup().getReadGroupId();
			Integer readGroupIndex = readGroupIdToIndexMap.get(readGroupId);
			cramRecord.setReadGroupID(readGroupIndex);
		}

		if (readAnnoReader != null)
			cramRecord.setAnnotations(readAnnoReader.nextReadAnnotations());

		if (!cramRecord.isReadMapped())
			unmappedRecordCount++;

		if (distanceToNextFragment > 0) {
			cramRecord.setLastFragment(false);
			cramRecord.setRecordsToNextFragment(distanceToNextFragment);
		} else
			cramRecord.setLastFragment(true);

		cramWriter.addRecord(cramRecord);

		if (tramPS != null)
			tramPS.println(cramRecordFormat.writeRecord(cramRecord));

		if (params.printCramRecords)
			System.out.println(cramRecordFormat.writeRecord(cramRecord));

		// treatQScores (record.getBaseQualities()) ;
		// System.out.println(record.format());
		baseCount += record.getReadLength();

	}

	private CramRecord buildCramRecord(SAMRecord samRecord) {
		return cramRecordFactory.createCramRecord(samRecord);
	}

	private static OutputStream createOutputStream(File outputCramFile, boolean wrapInGzip) throws IOException {
		OutputStream os = null;

		if (outputCramFile != null) {
			FileOutputStream cramFOS = new FileOutputStream(outputCramFile);
			if (wrapInGzip)
				os = new BufferedOutputStream(new GZIPOutputStream(cramFOS));
			else
				os = new BufferedOutputStream(cramFOS);
		} else
			os = new BufferedOutputStream(new FileOutputStream(outputCramFile + ".cram"));

		return os;
	}

	private static void printUsage(JCommander jc) {
		StringBuilder sb = new StringBuilder();
		sb.append("\n");
		jc.usage(sb);

		System.out.println(sb.toString());
	}

	public static void main(String[] args) throws Exception {
		Params params = new Params();
		JCommander jc = new JCommander(params);
		try {
			jc.parse(args);
		} catch (Exception e) {
			System.out.println(e.getMessage());
			printUsage(jc);
			return;
		}

		if (args.length == 0 || params.help) {
			printUsage(jc);
			return;
		}

		Bam2Cram b2c = new Bam2Cram(params);
		b2c.init();
		b2c.run();
		b2c.close();

		// long time = System.currentTimeMillis();
		// log.info(String.format("Compression time: %.3f seconds",
		// (System.currentTimeMillis() - time) / (float) 1000));
		// if (params.outputFile != null)
		// log.info(String.format("Compression, total: %.4f bits per base.",
		// 8f * params.outputFile.length() / bam2Cram_2.baseCount));
		//
		// os.close();
	}

	@Parameters(commandDescription = "BAM to CRAM converter.")
	static class Params {
		@Parameter(names = { "--input-bam-file" }, converter = FileConverter.class, description = "Path to a BAM file to be converted to CRAM.")
		File bamFile;

		@Parameter(names = { "--reference-fasta-file" }, converter = FileConverter.class, description = "The reference fasta file, uncompressed and indexed (.fai file, use 'samtools faidx'). ")
		File referenceFasta;

		@Parameter(names = { "--output-cram-file" }, converter = FileConverter.class, description = "The path for the output CRAM file.")
		File outputCramFile = null;

		@Parameter(names = { "--max-records" }, description = "Stop after compressing this many records. ")
		long maxRecords = Long.MAX_VALUE;

		@Parameter(names = { "--max-records-per-sequence" }, description = "For each reference sequence (aka chromosome) compress only this many records.")
		long maxRecordsPerSequence = Long.MAX_VALUE;

		@Parameter
		List<String> sequences;

		@Parameter(names = { "-h", "--help" }, description = "Print help and quit")
		boolean help = false;

		@Parameter(names = { "--round-trip-check" }, hidden = true)
		boolean roundTripCheck = false;

		@Parameter(names = { "--record-horizon" }, hidden = true)
		int spotAssemblyRecordsHorizon = 10000;

		@Parameter(names = { "--alignment-horizon" }, hidden = true)
		int spotAssemblyAlignmentHorizon = 10000;

		@Parameter(names = { "--max-block-size" }, hidden = true)
		int maxBlockSize = 100000;

		@Parameter(names = { "--read-quality-mask-file" }, converter = FileConverter.class, description = "Path to the file containing read quality masks.")
		File readQualityMaskFile;

		@Parameter(names = { "--fasta-style-rqm" }, description = "Read quality mask file is in 'fasta' style.")
		boolean fastaReadQualityMasking = false;

		@Parameter(names = { "--capture-unmapped-quality-scores" }, description = "Preserve quality scores for unmapped reads.")
		boolean captureUnmappedQualityScore = false;

		@Parameter(names = { "--capture-substitution-quality-scores" }, description = "Preserve quality scores for substitutions.")
		boolean captureSubstitutionQualityScore = false;

		@Parameter(names = { "--capture-insertion-quality-scores" }, description = "Preserve quality scores for insertions.")
		boolean captureInsertionQualityScore = false;

		@Parameter(names = { "--capture-masked-quality-scores" }, description = "Preserve quality scores for masked bases.")
		boolean captureMaskedQualityScore = false;

		@Parameter(names = { "--print-cram-records" }, description = "Print CRAM records while compressing.")
		boolean printCramRecords = false;

		@Parameter(names = { "--read-anno-file" }, converter = FileConverter.class, description = "Path to the read-level annotations file. ", hidden = true)
		File readAnnoFile;

		@Parameter(names = { "--stats-out-file" }, converter = FileConverter.class, description = "Print detailed statistics to this file.")
		File statsOutFile;

		@Parameter(names = { "--tram-out-file" }, converter = FileConverter.class, description = "Print textual representation of CRAM records to this file.")
		File tramOutFile;

		@Parameter(names = { "--quality-cutoff" }, description = "Preserve quality scores below this value.")
		int qualityCutoff = 0;

		@Parameter(names = { "--ref-snp-pos-file" }, converter = FileConverter.class, description = "Preserve quality scores for positions on the reference listed in this file.")
		File refSnpPosFile;

		@Parameter(names = { "--ignore-soft-clips" }, description = "Treat soft clips as hard clips.")
		boolean ignoreSoftClips = false;

		@Parameter(names = { "--capture-piled-quality-scores" }, description = "Preserve quality score where at least some reads disagree with the reference. See --mini-piled-hits.")
		boolean capturePiledQualityScores = false;

		@Parameter(names = { "--min-piled-hits" }, description = "Preserve quality score where at least this many reads disagree with the reference.")
		int minPiledHits = 2;
	}
}
