// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package pellet;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;

import aterm.ATermAppl;
import com.clarkparsia.modularity.IncrementalReasoner;
import com.clarkparsia.modularity.IncrementalReasonerConfiguration;
import com.clarkparsia.pellet.owlapiv3.OWLAPILoader;
import com.clarkparsia.pellet.owlapiv3.OWLClassTreePrinter;
import org.mindswap.pellet.KnowledgeBase;
import org.mindswap.pellet.PelletOptions;
import org.mindswap.pellet.taxonomy.printer.ClassTreePrinter;
import org.mindswap.pellet.taxonomy.printer.TaxonomyPrinter;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;

import static pellet.PelletCmdOptionArg.NONE;

/**
 * <p>
 * Title: PelletClassify
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2008
 * </p>
 * <p>
 * Company: Clark & Parsia, LLC. <http://www.clarkparsia.com>
 * </p>
 * 
 * @author Markus Stocker
 */
public class PelletClassify extends PelletCmdApp {
	
	/**
	 * Maximum radix for encoding of the MD5 of the root ontology URI
	 */
	private static final int ENCODING_RADIX = 36;
	
	/**
	 * The pattern for the names of the files containing the persisted data of the incremental classifier. The
	 * parameter in the pattern should be replaced with the MD5 of the root ontology IRI (to prevent mixing up
	 * files that belong to different ontologies). 
	 */
	private static final String FILE_NAME_PATTERN 	= "persisted-state-%s.zip";
	
	/**
	 * The directory where persisted state is saved (for now, this is the current directory).
	 */
	private File saveDirectory = new File( "." );
	
	/**
	 * Boolean flag whether the state of the classifier saved on disk is up-to-date
	 */
	private boolean currentStateSaved = false;

	public PelletClassify() {
	}

	@Override
	public String getAppCmd() {
		return "pellet classify " + getMandatoryOptions() + "[options] <file URI>...";
	}

	@Override
	public String getAppId() {
		return "PelletClassify: Classify the ontology and display the hierarchy";
	}

	@Override
	public PelletCmdOptions getOptions() {
		PelletCmdOptions options = getGlobalOptions();
		
		PelletCmdOption option = new PelletCmdOption( "persist" );
		option.setShortOption( "p" );
		option.setDescription( "Enable persistence of classification results. The classifier will save its internal state in a file, and will reuse it the next time this ontology is loaded, therefore saving classification time. This option can only be used with OWLAPIv3 loader." );
		option.setIsMandatory( false );
		option.setArg( NONE );
		options.add( option );
		
		options.add( getLoaderOption() );
		options.add( getIgnoreImportsOption() );
		options.add( getInputFormatOption() );
		
		return options;
	}

	@Override
	public void run() {
		if( options.getOption("persist").getValueAsBoolean() ) {
			runIncrementalClassify();
		}
		else {
			runClassicClassify();
		}
	}
	
	/**
	 * Performs classification using the non-incremental (classic) classifier
	 */
	private void runClassicClassify() {
		KnowledgeBase kb = getKB();
		
		startTask( "consistency check" );
		boolean isConsistent = kb.isConsistent();		
		finishTask( "consistency check" );

		if( !isConsistent )
			throw new PelletCmdException( "Ontology is inconsistent, run \"pellet explain\" to get the reason" );

		startTask( "classification" );
		kb.classify();
		finishTask( "classification" );

		TaxonomyPrinter<ATermAppl> printer = new ClassTreePrinter();
		printer.print( kb.getTaxonomy() );
	}
	
	/**
	 * Performs classification using the incremental classifier (and persisted data)
	 */
	private void runIncrementalClassify() {
		String loaderName = options.getOption( "loader" ).getValueAsString();
		
		if( !"OWLAPIv3".equals( loaderName ) ) {
			logger.log( Level.WARNING, "Ignoring -l " + loaderName + " option. When using --persist the only allowed loader is OWLAPIv3" );
		}
		
		OWLAPILoader loader = (OWLAPILoader) getLoader( "OWLAPIv3" );
		
		loader.parse( getInputFiles() );
		OWLOntology ontology = loader.getOntology();
		
		IncrementalReasoner incrementalClassifier = createIncrementalClassifier( ontology );
		
		if ( !incrementalClassifier.isClassified() ) {
			startTask( "consistency check" );
			boolean isConsistent = incrementalClassifier.isConsistent();
			finishTask( "consistency check" );

			if( !isConsistent )
				throw new PelletCmdException( "Ontology is inconsistent, run \"pellet explain\" to get the reason" );

			startTask( "classification" );
			incrementalClassifier.classify();
			finishTask( "classification" );
		}

		TaxonomyPrinter<OWLClass> printer = new OWLClassTreePrinter();
		printer.print( incrementalClassifier.getTaxonomy() );
		
		if( !currentStateSaved ) {
			persistIncrementalClassifier( incrementalClassifier, ontology );
		}
	}
	
	/**
	 * Creates incremental classifier by either creating it from scratch or by reading its state from file (if there exists such a state)
	 * @param ontology the ontology (the current state of it)
	 * @return the incremental classifier
	 */
	private IncrementalReasoner createIncrementalClassifier( OWLOntology ontology ) {
		File saveFile = determineSaveFile( ontology );

		IncrementalReasonerConfiguration config = IncrementalReasoner.config();

		// try to restore the classifier from the file (if one exists)
		if( saveFile.exists() ) {
			config.file(saveFile);
		}

		IncrementalReasoner result = new IncrementalReasoner(ontology, config);
		
		result.getReasoner().getKB().setTaxonomyBuilderProgressMonitor(
		                                                               PelletOptions.USE_CLASSIFICATION_MONITOR
		                                                                               .create());
		
		return result;
	}
	
	/**
	 * Stores the current state of the incremental classifier to a file (the file name is determined automatically
	 * based on ontology's IRI).
	 * 
	 * @param incrementalClassifier the incremental classifier to be stored
	 * @param ontology the ontology
	 */
	private void persistIncrementalClassifier( IncrementalReasoner incrementalClassifier, OWLOntology ontology ) {
		File saveFile = determineSaveFile( ontology );
		
		try {
			verbose( "Saving the state of the classifier to " + saveFile );
			incrementalClassifier.save(saveFile);
		}
        catch( IOException e ) {
        	logger.log( Level.WARNING, "Unable to persist the current classifier state: " + e.toString() );
        }		
	}
	
	/**
	 * Computes the name of the file to which the state of the incremental classifier will be persisted/read from.
	 * 
	 * @return the file name
	 */
	private File determineSaveFile( OWLOntology ontology ) {
		String fileName = String.format( FILE_NAME_PATTERN, hashOntologyIRI( ontology ) );
		
		return new File( saveDirectory, fileName );
	}
	
	/**
	 * Computes the hash code of the ontology IRI and returns the string representation of the hash code. The hash code
	 * is used to identify which files contain information about the particular ontology (and we can't use directly IRIs since they can contain special
	 * characters that are not allowed in file names, not to mention that this would make the file names too long).
	 * 
	 * @return the string representation of the hash code of the ontology IRI
	 */
	private String hashOntologyIRI( OWLOntology ontology ) {
		byte[] uriBytes = ontology.getOntologyID().getOntologyIRI().toString().getBytes();

		MessageDigest MD5 = null;
		
		try {
			MD5 = MessageDigest.getInstance( "MD5" );
		} catch( NoSuchAlgorithmException e ) {
			throw new PelletCmdException( "MD5 digest algorithm is not available." );
		}
		
		byte[] hashBytes = MD5.digest( uriBytes );
		
		BigInteger bi = new BigInteger( 1, hashBytes );
		
		return bi.toString( ENCODING_RADIX );
	}
}
