package beast.mascot.logger;

import java.io.PrintStream;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.math4.ode.FirstOrderDifferentialEquations;
import org.apache.commons.math4.ode.FirstOrderIntegrator;
import org.apache.commons.math4.ode.nonstiff.DormandPrince853Integrator;
import org.jblas.DoubleMatrix;

import beast.core.Function;
import beast.core.Input;
import beast.core.Loggable;
import beast.core.StateNode;
import beast.core.Input.Validate;
import beast.core.parameter.BooleanParameter;

import beast.evolution.branchratemodel.BranchRateModel;
import beast.evolution.tree.Node;
import beast.evolution.tree.TraitSet;
import beast.evolution.tree.Tree;
import beast.evolution.tree.coalescent.IntervalType;
import beast.mascot.distribution.StructuredRateIntervals;
import beast.mascot.distribution.StructuredTreeIntervals;
import beast.mascot.dynamicsAndTraits.variableTrait;
import beast.mascot.ode.MascotODEUpDown;


/**
 * @author adapted by Nicola Felix Mueller from the tree logger
 */
public class StructuredTreeLogger extends Tree implements Loggable {
	
	
	public Input<StructuredRateIntervals> structuredRateIntervals = new Input<>("rates", "Input of rates", Input.Validate.REQUIRED);
    public Input<Tree> treeInput = new Input<>("tree", "tree to be logged", Validate.REQUIRED);
    
    
    public Input<BranchRateModel.Base> clockModelInput = new Input<BranchRateModel.Base>("branchratemodel", "rate to be logged with branches of the tree");
    public Input<List<Function>> parameterInput = new Input<List<Function>>("metadata", "meta data to be logged with the tree nodes",new ArrayList<>());
    public Input<Boolean> maxStateInput = new Input<Boolean>("maxState", "report branch lengths as substitutions (branch length times clock rate for the branch)", false);
    public Input<BooleanParameter> conditionalStateProbsInput = new Input<BooleanParameter>("conditionalStateProbs", "report branch lengths as substitutions (branch length times clock rate for the branch)");
    public Input<Boolean> substitutionsInput = new Input<Boolean>("substitutions", "report branch lengths as substitutions (branch length times clock rate for the branch)", false);
    public Input<Integer> decimalPlacesInput = new Input<Integer>("dp", "the number of decimal places to use writing branch lengths and rates, use -1 for full precision (default = full precision)", -1);
    public Input<TraitSet> typeTraitInput = new Input<>("typeTrait", "Type trait set.");   
    public Input<variableTrait> variableTraitInput = new Input<>("variableTrait","traits that can change");

    
    boolean someMetaDataNeedsLogging;
    boolean substitutions = false;
    boolean takeMax = true;
    boolean conditionals = true;
    
    boolean updown = true;

    private DecimalFormat df;
    private String type;
    private boolean traitInput = false;
    
    private int states;
	
	
    @Override
    public void initAndValidate() {  	
    	
        if (typeTraitInput.get() != null) traitInput = true;    

    	
        if (parameterInput.get().size() == 0 && clockModelInput.get() == null) {
        	someMetaDataNeedsLogging = false;
        	return;
            //throw new Exception("At least one of the metadata and branchratemodel inputs must be defined");
        }
    	someMetaDataNeedsLogging = true;
    	// without substitution model, reporting substitutions == reporting branch lengths 
        if (clockModelInput.get() != null) {
        	substitutions = substitutionsInput.get();
        }
       
        if (maxStateInput.get() != null){
        	takeMax = maxStateInput.get();
        	
        }

        int dp = decimalPlacesInput.get();

        if (dp < 0) {
            df = null;
        } else {
            // just new DecimalFormat("#.######") (with dp time '#' after the decimal)
            df = new DecimalFormat("#."+new String(new char[dp]).replace('\0', '#'));
            df.setRoundingMode(RoundingMode.HALF_UP);
        }             
        
        states = 0;    
    }

    @Override
    public void init(PrintStream out) {
    	treeInput.get().init(out);
    }

    @Override
    public void log(int nSample, PrintStream out) {
    	
        states = 0;       
        
        	
           	
    	states = structuredRateIntervals.get().getDimension();
    	
        // make sure we get the current version of the inputs
        Tree tree = (Tree) treeInput.get().getCurrent();
        //calculate the state of each node
    	CalculateNodeStates(tree);
    	
        List<Function> metadata = parameterInput.get();
        for (int i = 0; i < metadata.size(); i++) {
        	if (metadata.get(i) instanceof StateNode) {
        		metadata.set(i, ((StateNode) metadata.get(i)).getCurrent());
        	}
        }
        BranchRateModel.Base branchRateModel = clockModelInput.get();
        // write out the log tree with meta data
        out.print("tree STATE_" + nSample + " = ");
        out.print(toNewick(tree.getRoot(), metadata, branchRateModel));
        out.print(";");
    }


	/**
     * Appends a double to the given StringBuffer, formatting it using
     * the private DecimalFormat instance, if the input 'dp' has been
     * given a non-negative integer, otherwise just uses default
     * formatting.
     * @param buf
     * @param d
     */
    private void appendDouble(StringBuffer buf, double d) {
        if (df == null) {
            buf.append(d);
        } else {
            buf.append(df.format(d));
        }
    }

    String toNewick(Node node, List<Function> metadataList, BranchRateModel.Base branchRateModel) {
        if (maxStateInput.get() != null){
        	takeMax = maxStateInput.get();
        	
        }
        StringBuffer buf = new StringBuffer();
        if (node.getLeft() != null) {
            buf.append("(");
            buf.append(toNewick(node.getLeft(), metadataList, branchRateModel));
            if (node.getRight() != null) {
                buf.append(',');
                buf.append(toNewick(node.getRight(), metadataList, branchRateModel));
            }
            buf.append(")");
        } else {
            buf.append(node.getNr() + 1);
        }
        if (!node.isLeaf()) {
        	if (!takeMax){	        
		        buf.append("[&" + type + "prob={");
		        
		        DoubleMatrix stateProbs = new DoubleMatrix();
		        
	        	stateProbs = getStateProb(node.getNr());		        
		        
		        for (int i = 0 ; i < states-1; i++){
		        	buf.append(String.format("%.3f", stateProbs.get(i)));
		        	buf.append(",");
		        }
		        buf.append(String.format("%.3f", stateProbs.get(states-1)));
		        buf.append("}");
	        
		        buf.append(",max" + type + "=");
		        buf.append(String.format("%d", stateProbs.argmax() ));
		        buf.append(']');
        	}else{
		        buf.append("[&max" + type + "=");
		        DoubleMatrix stateProbs = new DoubleMatrix();
		        
	        	stateProbs = getStateProb(node.getNr());

		        buf.append(String.format("%d", stateProbs.argmax() ));
		        buf.append(']');        		
        	}
        }else{
			String sampleID = node.getID();
			String[] splits = sampleID.split("_");
			int sampleState;
			
			if(traitInput){				
				sampleState = (int) typeTraitInput.get().getValue(node.getID());
			}
			
			if(variableTraitInput.get() != null)
				sampleState = (int) variableTraitInput.get().getSamplingState(node.getID());
			
			else{
				sampleState = Integer.parseInt(splits[splits.length-1]); //samples states (or priors) should eventually be specified in the XML
			}
			if (!takeMax){
    	        
		        buf.append("[&" + type + "prob={");
	
		        for (int i = 0 ; i < states-1; i++){
		        	if (sampleState != i) buf.append(String.format("0.0"));
		        	if (sampleState == i) buf.append(String.format("1.0"));
	            	buf.append(",");
		        }

	        	if (sampleState != states-1) buf.append(String.format("0.0"));
	        	if (sampleState == states-1) buf.append(String.format("1.0"));
		        
		        buf.append("}");
		        buf.append(",max" + type + "=");

		        buf.append(String.format("%d", sampleState ));
//		        buf.append(']'); 
		        buf.append(']');
        	}else{
		        buf.append("[&max" + type + "=");

		        buf.append(String.format("%d", sampleState ));
		        buf.append(']');        		
        	}
        }
        
        buf.append(":");
        if (substitutions) {
            appendDouble(buf, node.getLength() * branchRateModel.getRateForBranch(node));
        } else {
            appendDouble(buf, node.getLength());
        }
        return buf.toString();
    }

	@Override
    public void close(PrintStream out) {
    	treeInput.get().close(out);
    }
	
	//===================================================
	//===================================================
	// Calculate the state of all nodes using the up-down
	// algorithm
	//===================================================
	//===================================================
	public int samples;
	public int nrSamples;
	public DoubleMatrix[] stateProbabilities;
	public DoubleMatrix[] stateProbabilitiesDown;
	public DoubleMatrix[] TransitionProbabilities;	  
    
    private int nrLineages;   

    // current rates         
    private double[][] migrationRates;
    private double[] coalescentRates; 	

    
    // Set up for lineage state probabilities
    private ArrayList<Integer> activeLineages;
	private double[] linProbs;
	private double[] transitionProbs;
	
	// maximum integration error tolerance
    private double maxTolerance = 1e-5;            
    private boolean recalculateLogP;   
    private StructuredTreeIntervals sti;

	
    private void CalculateNodeStates(Tree tree){  
    	// initialize the tree intervals   	
    	sti = new StructuredTreeIntervals(tree);    	
    	// newly calculate tree intervals
    	sti.calculateIntervals();
    	// correctly calculate the daughter nodes at coalescent intervals in the case of
    	// bifurcation or in case two nodes are at the same height
    	sti.swap();    	
    	
    	stateProbabilities = new DoubleMatrix[sti.getSampleCount()];
    	stateProbabilitiesDown = new DoubleMatrix[sti.getSampleCount()];
    	TransitionProbabilities = new DoubleMatrix[sti.getSampleCount()*2];        
        nrSamples = sti.getSampleCount() + 1;        

    	
        // Set up ArrayLists for the indices of active lineages and the lineage state probabilities
        activeLineages = new ArrayList<Integer>(); 

        nrLineages = 0;
        linProbs = new double[0];// initialize the tree and rates interval counter
        transitionProbs = new double[0];// initialize the tree and rates interval counter
        
        int treeInterval = 0, ratesInterval = 0;        
        double nextEventTime = 0.0;
        migrationRates = structuredRateIntervals.get().getIntervalMigRate(ratesInterval);
		coalescentRates = structuredRateIntervals.get().getIntervalCoalRate(ratesInterval);  
        // Time to the next rate shift or event on the tree
        double nextTreeEvent = sti.getInterval(treeInterval);
        double nextRateShift = structuredRateIntervals.get().getInterval(ratesInterval);

             

        int currTreeInterval = 0; 													// what tree interval are we in?
        // Calculate the likelihood
        do {       
        	nextEventTime = Math.min(nextTreeEvent, nextRateShift); 	 
        	
        	if (nextEventTime > 0) {													// if true, calculate the interval contribution        		
                if(recalculateLogP){
    				System.err.println("ode calculation stuck, reducing tolerance, new tolerance= " + maxTolerance);
    				maxTolerance *=0.9;
    				System.exit(0);
                	CalculateNodeStates(tree);
                	return;
                }
	        	double[] probs_for_ode = new double[linProbs.length + transitionProbs.length];   
	        	double[] oldLinProbs = new double[linProbs.length + transitionProbs.length]; 
	        	
                for (int i = 0; i<linProbs.length; i++)
                	oldLinProbs[i] = linProbs[i];  
                for (int i = linProbs.length; i < transitionProbs.length; i++)
                	oldLinProbs[i] = transitionProbs[i-linProbs.length];  
	        	
	        	
                FirstOrderIntegrator integrator = new DormandPrince853Integrator(1e-32, 1e10, maxTolerance, 1e-100);
                // set the maximal number of evaluations
                integrator.setMaxEvaluations((int) 1e10);
                // set the odes
                FirstOrderDifferentialEquations ode = new MascotODEUpDown(migrationRates, coalescentRates, nrLineages , coalescentRates.length);

                // integrate	        
                try {
                	integrator.integrate(ode, 0, oldLinProbs, nextEventTime, probs_for_ode);
                }catch(Exception e){
                	System.out.println(e);
                	System.out.println("expection");
                	recalculateLogP = true;    				
                }        		       	
	           
                for (int i = 0; i<linProbs.length; i++)
            		linProbs[i] = probs_for_ode[i];  
                for (int i = linProbs.length; i < transitionProbs.length; i++)
            		transitionProbs[i-linProbs.length] = probs_for_ode[i];  
    		}
        	
        	if (nextTreeEvent < nextRateShift){
 	        	if (sti.getIntervalType(treeInterval) == IntervalType.COALESCENT) {
 	        		nrLineages--;													// coalescent event reduces the number of lineages by one
	        		normalizeLineages();									// normalize all lineages before event		
        			coalesce(treeInterval);	  				// calculate the likelihood of the coalescent event
	        	}
 	       		
 	       		if (sti.getIntervalType(treeInterval) == IntervalType.SAMPLE) { 	
 	       			nrLineages++;													// sampling event increases the number of lineages by one
 	       			if (linProbs.length > 0)
 	       				normalizeLineages();								// normalize all lineages before event
 	       			sample(treeInterval);							// calculate the likelihood of the sampling event if sampling rate is given
	       		}	
 	       		
 	       		treeInterval++;
        		nextRateShift -= nextTreeEvent;   
        		try{
        			nextTreeEvent = sti.getInterval(treeInterval);
        		}catch(Exception e){
        			break;
        		}
        	}else{
        		ratesInterval++;
 	       		migrationRates = structuredRateIntervals.get().getIntervalMigRate(ratesInterval);
 	       		coalescentRates = structuredRateIntervals.get().getIntervalCoalRate(ratesInterval);        		
        		nextTreeEvent -= nextRateShift;
 	       		nextRateShift = structuredRateIntervals.get().getInterval(ratesInterval);
        	}
        	
        }while(nextTreeEvent <= Double.POSITIVE_INFINITY);
        
        boolean isRoot = true;
        do{
		  	if (sti.getIntervalType(currTreeInterval) == IntervalType.COALESCENT) {
		  		coalesceDown(currTreeInterval, isRoot);									// Set parent lineage state probs and remove children
		  		isRoot = false;
		   	}       	
		  	currTreeInterval--;
        }while(currTreeInterval>=0);
    }
	

    private double normalizeLineages(){
    	if (linProbs==null)
    		return 0.0;
    	
    	
    	double interval = 0.0;
    	for (int i = 0; i < linProbs.length/states; i++){
    		double lineProbs = 0.0;
    		for (int j = 0; j < states; j++)
    			if (linProbs[i*states+j]>=0.0){
    				lineProbs += linProbs[i*states+j];
    			}else{
    				// try recalculation after lowering the tolerance
    				recalculateLogP = true;
    				return Math.log(1.0);
    			}
    		for (int j = 0; j < states; j++){
    			linProbs[i*states+j] = linProbs[i*states+j]/lineProbs;
    		}    		
    		interval +=lineProbs;
    	}	
    	
		// return mean P_t(T)
		return Math.log(interval/(linProbs.length/states));

    }
    
    private void sample(int currTreeInterval) {
		List<Node> incomingLines = sti.getLineagesAdded(currTreeInterval);
		// calculate the new length of the arrays for the transition and lineage states
		int newLengthLineages = linProbs.length + incomingLines.size()*states;
		int newLengthTransitions = transitionProbs.length + incomingLines.size()*states*states;
		
		double[] linProbsNew = new double[newLengthLineages];		
		double[] transitionProbsNew = new double[newLengthTransitions];		
		
		for (int i = 0; i < linProbs.length; i++)
			linProbsNew[i] = linProbs[i];	
		
		for (int i = 0; i < transitionProbs.length; i++)
			transitionProbsNew[i] = transitionProbs[i];		
		
		int currPositionLineages = linProbs.length;
		int currPositionTransitions = transitionProbs.length;
		/*
		 * If there is no trait given as Input, the model will simply assume that
		 * the last value of the taxon name, the last value after a _, is an integer
		 * that gives the type of that taxon
		 */
		for (Node l : incomingLines) {
			activeLineages.add(l.getNr());
			String sampleID = l.getID();
			int sampleState = 0;
			if (states > 1){				
				String[] splits = sampleID.split("_");
				sampleState = Integer.parseInt(splits[splits.length-1]); //samples states (or priors) should eventually be specified in the XML
			}
			for (int i = 0; i< states; i++){
				if (i == sampleState){
					linProbsNew[currPositionLineages] = 1.0;currPositionLineages++;
				}
				else{
					linProbsNew[currPositionLineages] = 0.0;currPositionLineages++;
				}
			}
			// add the initial transition probabilities (diagonal matrix)
			for (int s = 0; s < states; s++)
				for (int i = 0; i < states; i++)
					if (i == s){
						transitionProbsNew[currPositionTransitions] = 1.0;
						currPositionTransitions++;
					}else{
						transitionProbsNew[currPositionTransitions] = 0.0;
						currPositionTransitions++;
					}

		}	
		linProbs = linProbsNew;
		transitionProbs = transitionProbsNew;

    }
    
    private void coalesce(int currTreeInterval) {

    	// normalize the transition probabilities
    	for (int i = 0; i < nrLineages*states; i++){
    		double lineProbs = 0.0;
    		for (int j = 0; j < states; j++){
    			if (transitionProbs.length>=0.0){
    				lineProbs += transitionProbs[i*states+j];
    			}else{
    				System.err.println("transition probability smaller than 0 (or NaN before normalizing");	    				
    				System.exit(1);
    			}
    		}
    		for (int j = 0; j < states; j++)
    			transitionProbs[i*states+j] = transitionProbs[states*i+j]/lineProbs; 	    		
    	}

    	

		List<Node> coalLines = sti.getLineagesRemoved(currTreeInterval);
    	if (coalLines.size() > 2) {
			System.err.println("Unsupported coalescent at non-binary node");
			System.exit(0);
		}
    	if (coalLines.size() < 2) {
    		System.out.println();
    		System.out.println("WARNING: Less than two lineages found at coalescent event!");
    		System.exit(0);
		}
		
    	final int daughterIndex1 = activeLineages.indexOf(coalLines.get(0).getNr());
		final int daughterIndex2 = activeLineages.indexOf(coalLines.get(1).getNr());
		if (daughterIndex1 == -1 || daughterIndex2 == -1) {
			System.out.println("daughter lineages at coalescent event not found");
    		System.exit(0);
		}
		DoubleMatrix lambda = DoubleMatrix.zeros(states);

		/*
		 * Calculate the overall probability for two strains to coalesce 
		 * independent of the state at which this coalescent event is 
		 * supposed to happen
		 */
        for (int k = 0; k < states; k++) { 
        	Double pairCoalRate = coalescentRates[k] * 2 * linProbs[daughterIndex1*states + k] * linProbs[daughterIndex2*states + k];			
			if (!Double.isNaN(pairCoalRate)){
				lambda.put(k, pairCoalRate);
			}else{
	    		System.exit(0);
			}
        }
        
        activeLineages.add(coalLines.get(0).getParent().getNr());
        
        
        // get the node state probabilities
		DoubleMatrix pVec = new DoubleMatrix();
		pVec.copy(lambda);
		pVec = pVec.div(pVec.sum());
		
		// save the node states conditioned on the subtree
		stateProbabilities[coalLines.get(0).getParent().getNr() - nrSamples] = pVec;

		
		// get the transition probabilities of daughter lineage 1
		DoubleMatrix tP1 = DoubleMatrix.zeros(states,states);
		for (int i = 0; i< states; i++){
			for (int j = 0; j< states; j++){
				tP1.put(i, j, transitionProbs[daughterIndex1*states*states+i*states+j]);
			}
		}
		// get the transition probabilities of daughter lineage 2
		DoubleMatrix tP2 = DoubleMatrix.zeros(states,states);
		for (int i = 0; i< states; i++){
			for (int j = 0; j< states; j++){
				tP2.put(i, j, transitionProbs[daughterIndex2*states*states+i*states+j]);
			}
		}	
		
		double[] linProbsNew  = new double[linProbs.length - states];

		int linCount = 0;		
		// add all lineages execpt the daughter lineage to the new p array
		for (int i = 0; i <= nrLineages; i++){
			if (i != daughterIndex1 && i != daughterIndex2){
				for (int j = 0; j < states; j++){
					linProbsNew[linCount*states + j] = linProbs[i*states + j];
				}
				linCount++;
			}
		}
		// add the parent lineage
		for (int j = 0; j < states; j++){
			linProbsNew[linCount*states + j] = pVec.get(j);
		}
		// set p to pnew
		linProbs = linProbsNew;	

		
		double[] transitionProbsNew  = new double[transitionProbs.length - states*states];
		
		// add initial transition probabilities for the parent lineage
		linCount = 0;
		for (int i = 0; i <= nrLineages; i++){
			if (i != daughterIndex1 && i != daughterIndex2){
				for (int j = 0; j < states; j++)
					for (int k = 0; k < states; k++)
						transitionProbsNew[linCount*states*states+j*states+k] 
								= transitionProbs[i*states*states+j*states+k];
				linCount++;
			}
		}

		for (int j = 0; j < states; j++)
			for (int k = 0; k < states; k++)
				if (j==k)
					transitionProbsNew[linCount*states*states+j*states+k] = 1.0;
				else
					transitionProbsNew[linCount*states*states+j*states+k] = 0.0;

		// set the transition probs
		transitionProbs = transitionProbsNew;
		
		// save the transition probabilities of each of the two daughter lineages
		TransitionProbabilities[coalLines.get(0).getNr()] = tP1;
		TransitionProbabilities[coalLines.get(1).getNr()] = tP2;
		
		//Remove daughter lineages from the line state probs and the transition probs
		if (daughterIndex1>daughterIndex2){
			// remove the daughter lineages from the active lineages
			activeLineages.remove(daughterIndex1);
			activeLineages.remove(daughterIndex2);			
		}else{
			// remove the daughter lineages from the active lineages
			activeLineages.remove(daughterIndex2);
			activeLineages.remove(daughterIndex1);
		}
    }

    private void coalesceDown(int currTreeInterval, boolean isRoot) {
		List<Node> parentLines = sti.getLineagesAdded(currTreeInterval);
		Node parentNode = parentLines.get(0);
		
		if (!isRoot){
			DoubleMatrix start = stateProbabilities[parentNode.getNr() - nrSamples];
			DoubleMatrix end = stateProbabilities[parentNode.getParent().getNr() - nrSamples];
			DoubleMatrix flow = TransitionProbabilities[parentNode.getNr()];
			DoubleMatrix otherSideInfo = end.div(start.transpose().mmul(flow));
			DoubleMatrix conditional = flow.mmul(otherSideInfo);
			conditional = conditional.mul(start);
			stateProbabilities[parentNode.getNr() - nrSamples] = conditional.div(conditional.sum());
		}
    }

    
	private DoubleMatrix getStateProb(int nr) {
		// TODO Auto-generated method stub
		return stateProbabilities[nr - nrSamples] ;
	}


	
}