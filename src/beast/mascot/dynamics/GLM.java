package beast.mascot.dynamics;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;

import org.apache.commons.math3.util.FastMath;

import beast.core.Description;
import beast.core.Input;
import beast.core.Input.Validate;
import beast.core.Loggable;
import beast.core.parameter.RealParameter;
import beast.mascot.glmmodel.GlmModel;


@Description("Extracts the intervals from a tree. Points in the intervals " +
        "are defined by the heights of nodes in the tree.")
public class GLM extends Dynamics implements Loggable {	
    
	public Input<GlmModel> migrationGLMInput = new Input<>(
			"migrationGLM", "input of migration GLM model", Validate.REQUIRED);
    
	public Input<GlmModel> NeGLMInput = new Input<>(
			"NeGLM", "input of migration GLM model", Validate.REQUIRED);
    
    public Input<RealParameter> rateShiftsInput = new Input<>(
    		"rateShifts", "input of timings of rate shifts relative to the most recent sample", Validate.OPTIONAL);    
     
	public Input<Double> maxRateInput = new Input<>(
			"maxRate", "maximum rate used for integration", Double.POSITIVE_INFINITY);

	double[] intTimes;
	
 
	int firstlargerzero;
	
	public GLM(){
    	typesInput.setRule(Input.Validate.REQUIRED);		
	}
	
    @Override
    public void initAndValidate() {
    	
    	// if there are rate shifts as an input, use the stepwise glm model otherwise the constant
    	if (rateShiftsInput.get() != null){
	    	intTimes = new double[(int) rateShiftsInput.get().getDimension()];
	    	intTimes[0] = rateShiftsInput.get().getArrayValue(0);
	    	for (int i = 1; i < rateShiftsInput.get().getDimension(); i++){
	    		if (rateShiftsInput.get().getArrayValue(i-1)>=0){
	    			intTimes[i] = rateShiftsInput.get().getArrayValue(i) - rateShiftsInput.get().getArrayValue(i-1); 
	    		}else{
	    			intTimes[i] = rateShiftsInput.get().getArrayValue(i);
	    		}
	    			
	    	}
	    }else{
	    	intTimes = new double[1];
	    	intTimes[0] = Double.POSITIVE_INFINITY;
    	}
    	
    	// check which rateshiftInput is the first above 0
    	firstlargerzero = intTimes.length-1;
    	for (int i = 0 ; i < intTimes.length; i++){
    		if (intTimes[i] >  0){
    			firstlargerzero = i;
				break;
    		}
    	}
    	
    	String[] splittedTypes = typesInput.get().split("\\s+");

    	// check which rateshiftInput is the first above 0
    	firstlargerzero = intTimes.length-1;
    	for (int i = 0 ; i < intTimes.length; i++){
    		if (intTimes[i] >  0){
    			firstlargerzero = i;
				break;
    		}
    	}
    	
		dimensionInput.set(splittedTypes.length);
    	
		traitToType = new HashMap<>();
		reverseTraitToType = new HashMap<>();
		for (int i = 0; i < splittedTypes.length; i++)
			traitToType.put(splittedTypes[i], i);
		for (int i = 0; i < splittedTypes.length; i++)
			reverseTraitToType.put(i, splittedTypes[i]);
		
		// set the number of intervals for the GLM models
		migrationGLMInput.get().setNrIntervals(rateShiftsInput.get().getDimension());
		NeGLMInput.get().setNrIntervals(rateShiftsInput.get().getDimension());
    }

    /**
     * Returns the time to the next interval.
     */
    @Override
    public double getInterval(int i) {
    	if (i >= rateShiftsInput.get().getDimension()-firstlargerzero){
     		return Double.POSITIVE_INFINITY;
     	}else{
			return intTimes[i+firstlargerzero];
     	}
    }   

    @Override
    public double[] getIntervals() {
    	return intTimes;
    }
    
    public boolean intervalIsDirty(int i){
		if(NeGLMInput.get().isDirty())
			return true;
		if(migrationGLMInput.get().isDirty())
			return true;
    	return false;
    }  
    

    
	@Override
    public double[] getCoalescentRate(int i){
		int intervalNr;
    	if (i >= rateShiftsInput.get().getDimension()-firstlargerzero)
    		intervalNr = rateShiftsInput.get().getDimension()-1;
    	else
    		intervalNr = i + firstlargerzero;

    	double[] Ne = NeGLMInput.get().getRates(intervalNr);
		double[] coal = new double[Ne.length];
		for (int j = 0; j < Ne.length; j++){
			coal[j] = FastMath.min(1/Ne[j],maxRateInput.get());
		}
		return coal;
    }
    
	@Override    
    public double[] getBackwardsMigration(int i){
		int intervalNr;
    	if (i >= rateShiftsInput.get().getDimension()-firstlargerzero)
    		intervalNr = rateShiftsInput.get().getDimension()-1;
    	else
    		intervalNr = i + firstlargerzero;

    	int n = dimensionInput.get();
    	double[] m = new double[n * n];
		double[] mig = migrationGLMInput.get().getRates(intervalNr);
		double[] Ne = NeGLMInput.get().getRates(intervalNr);
		
		int c = 0;
		for (int a = 0; a < dimensionInput.get(); a++){
			for (int b = 0; b < dimensionInput.get(); b++){
				if (a!=b){
					m[b * n + a] = FastMath.min( 
							Ne[a]*mig[c]/Ne[b],
							maxRateInput.get());
					c++;
				}
			}
		}
		return m;
    }

	@Override
	public void recalculate() {
		// TODO Auto-generated method stub
		
	}    
	
	public Double[] getAllCoalescentRate() {
		Double[] coal = new Double[NeGLMInput.get().nrIntervals*NeGLMInput.get().verticalEntries];
		
		for (int i = 0; i < intTimes.length; i++){
	    	double[] Ne = NeGLMInput.get().getRates(i);
	    	for (int j = 0; j < Ne.length; j++)
	    		coal[i*NeGLMInput.get().verticalEntries + j] = 1/Ne[j];
		}
		return coal;
	}

	public Double[] getAllBackwardsMigration() {
		Double[] mig = new Double[migrationGLMInput.get().nrIntervals*migrationGLMInput.get().verticalEntries];
		
		for (int i = 0; i < intTimes.length; i++){
	    	double[] m = migrationGLMInput.get().getRates(i);
	    	for (int j = 0; j < m.length; j++)
	    		mig[i*migrationGLMInput.get().verticalEntries + j] = m[j];
		}
		return mig;
	}

	@Override
	public void init(PrintStream out) {
		for (int j = 0; j < dimensionInput.get(); j++){
			for (int i = 0; i < intTimes.length; i++){
				out.print(String.format("Ne.%d.%d\t", j,i));
			}			
		}
	}

	@Override
	public void log(long sample, PrintStream out) {
		for (int j = 0; j < dimensionInput.get(); j++){
			for (int i = 0; i < intTimes.length; i++){
		    	double[] Ne = NeGLMInput.get().getRates(i);
				out.print(Ne[j] + "\t");
			}			
		}
	}

	@Override
	public void close(PrintStream out) {
		// TODO Auto-generated method stub
		
	}

//    @Override
//	protected boolean requiresRecalculation(){
//    	
//    	return intervalIsDirty(0);
//    }


    @Override
    public int getEpochCount() {
    	return rateShiftsInput.get().getDimension();
    }
}