import org.apache.commons.lang.StringUtils
import org.hibernate.Query
import org.hibernate.SessionFactory;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.module.bahmniemrapi.encountertransaction.contract.BahmniObservation
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.api.context.Context;
import org.openmrs.module.bahmniemrapi.obscalculator.ObsValueCalculator;
import org.openmrs.module.bahmniemrapi.encountertransaction.contract.BahmniEncounterTransaction
import org.openmrs.module.emrapi.encounter.domain.EncounterTransaction;

import org.joda.time.LocalDate;
import org.joda.time.Months;

public class BahmniObsValueCalculator implements ObsValueCalculator {

    static Double BMI_VERY_SEVERELY_UNDERWEIGHT = 16.0;
    static Double BMI_SEVERELY_UNDERWEIGHT = 17.0;
    static Double BMI_UNDERWEIGHT = 18.5;
    static Double BMI_NORMAL = 25.0;
    static Double BMI_OVERWEIGHT = 30.0;
    static Double BMI_OBESE = 35.0;
    static Double BMI_SEVERELY_OBESE = 40.0;
    static Double ZERO = 0.0;
    static Map<BahmniObservation, BahmniObservation> obsParentMap = new HashMap<BahmniObservation, BahmniObservation>();

    public static enum BmiStatus {
        VERY_SEVERELY_UNDERWEIGHT("Very Severely Underweight"),
        SEVERELY_UNDERWEIGHT("Severely Underweight"),
        UNDERWEIGHT("Underweight"),
        NORMAL("Normal"),
        OVERWEIGHT("Overweight"),
        OBESE("Obese"),
        SEVERELY_OBESE("Severely Obese"),
        VERY_SEVERELY_OBESE("Very Severely Obese");

        private String status;

        BmiStatus(String status) {
            this.status = status
        }

        @Override
        public String toString() {
            return status;
        }
    }


    public void run(BahmniEncounterTransaction bahmniEncounterTransaction) {
        calculateAndAdd(bahmniEncounterTransaction);
    }

 static def calculateAndAdd(BahmniEncounterTransaction bahmniEncounterTransaction) {
        Collection<BahmniObservation> observations = bahmniEncounterTransaction.getObservations()
        setAutisticHyperactivityTotal( observations, bahmniEncounterTransaction)
        setUncivilizedBehaviourChecklistTotal( observations, bahmniEncounterTransaction)
	setBMIDetails(observations, bahmniEncounterTransaction)
       	setObstetricsEDD(observations, bahmniEncounterTransaction)
        setANCEDD(observations, bahmniEncounterTransaction)
        setWaistHipRatio(observations, bahmniEncounterTransaction)
    
}

private
    static void setBMIDetails(Collection<BahmniObservation> observations, BahmniEncounterTransaction bahmniEncounterTransaction) {      

	BahmniObservation heightObservation = find("Height", observations, null)
        BahmniObservation weightObservation = find("Weight", observations, null)
        BahmniObservation parent = null;
        def nowAsOfEncounter = bahmniEncounterTransaction.getEncounterDateTime() != null ? bahmniEncounterTransaction.getEncounterDateTime() : new Date();

        if (hasValue(heightObservation) || hasValue(weightObservation)) {
            BahmniObservation bmiDataObservation = find("BMI Data", observations, null)
            BahmniObservation bmiObservation = find("BMI", bmiDataObservation ? [bmiDataObservation] : [], null)
            BahmniObservation bmiAbnormalObservation = find("BMI Abnormal", bmiDataObservation ? [bmiDataObservation]: [], null)

            BahmniObservation bmiStatusDataObservation = find("BMI Status Data", observations, null)
            BahmniObservation bmiStatusObservation = find("BMI Status", bmiStatusDataObservation ? [bmiStatusDataObservation] : [], null)
            BahmniObservation bmiStatusAbnormalObservation = find("BMI Status Abnormal", bmiStatusDataObservation ? [bmiStatusDataObservation]: [], null)

            Patient patient = Context.getPatientService().getPatientByUuid(bahmniEncounterTransaction.getPatientUuid())
            def patientAgeInMonthsAsOfEncounter = Months.monthsBetween(new LocalDate(patient.getBirthdate()), new LocalDate(nowAsOfEncounter)).getMonths()

            parent = obsParent(heightObservation, parent)
            parent = obsParent(weightObservation, parent)

            if ((heightObservation && heightObservation.voided) && (weightObservation && weightObservation.voided)) {
                voidObs(bmiDataObservation);
                voidObs(bmiObservation);
                voidObs(bmiStatusDataObservation);
                voidObs(bmiStatusObservation);
                voidObs(bmiAbnormalObservation);
                return;
            }

            def previousHeightValue = fetchLatestValue("Height", bahmniEncounterTransaction.getPatientUuid(), heightObservation, nowAsOfEncounter)
            def previousWeightValue = fetchLatestValue("Weight", bahmniEncounterTransaction.getPatientUuid(), weightObservation, nowAsOfEncounter)

            Double height = hasValue(heightObservation) && !heightObservation.voided ? heightObservation.getValue() as Double : previousHeightValue
            Double weight = hasValue(weightObservation) && !weightObservation.voided ? weightObservation.getValue() as Double : previousWeightValue
            Date obsDatetime = getDate(weightObservation) != null ? getDate(weightObservation) : getDate(heightObservation)

            if (height == null || weight == null) {
                voidObs(bmiDataObservation)
                voidObs(bmiObservation)
                voidObs(bmiStatusDataObservation)
                voidObs(bmiStatusObservation)
                voidObs(bmiAbnormalObservation)
                return;
            }

            bmiDataObservation = bmiDataObservation ?: createObs("BMI Data", null, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
            bmiStatusDataObservation = bmiStatusDataObservation ?: createObs("BMI Status Data", null, bahmniEncounterTransaction, obsDatetime) as BahmniObservation

            def bmi = bmi(height, weight)
            bmiObservation = bmiObservation ?: createObs("BMI", bmiDataObservation, bahmniEncounterTransaction, obsDatetime) as BahmniObservation;
            bmiObservation.setValue(bmi);

            def bmiStatus = bmiStatus(bmi, patientAgeInMonthsAsOfEncounter, patient.getGender());
            bmiStatusObservation = bmiStatusObservation ?: createObs("BMI Status", bmiStatusDataObservation, bahmniEncounterTransaction, obsDatetime) as BahmniObservation;
            bmiStatusObservation.setValue(bmiStatus);

            def bmiAbnormal = bmiAbnormal(bmiStatus);
            bmiAbnormalObservation =  bmiAbnormalObservation ?: createObs("BMI Abnormal", bmiDataObservation, bahmniEncounterTransaction, obsDatetime) as BahmniObservation;
            bmiAbnormalObservation.setValue(bmiAbnormal);

            bmiStatusAbnormalObservation =  bmiStatusAbnormalObservation ?: createObs("BMI Status Abnormal", bmiStatusDataObservation, bahmniEncounterTransaction, obsDatetime) as BahmniObservation;
            bmiStatusAbnormalObservation.setValue(bmiAbnormal);

            return;
        }
}

private
    static void setObstetricsEDD(Collection<BahmniObservation> observations, BahmniEncounterTransaction bahmniEncounterTransaction) {
        BahmniObservation lmpObservation = find("Obstetrics, Last Menstrual Period", observations, null)
        def calculatedConceptName = "Estimated Date of Delivery"
        BahmniObservation parent = null;
        if (hasValue(lmpObservation)) {
            parent = obsParent(lmpObservation, null)
            def calculatedObs = find(calculatedConceptName, observations, null)

            Date obsDatetime = getDate(lmpObservation)

            LocalDate edd = new LocalDate(lmpObservation.getValue()).plusMonths(9).plusWeeks(1)
            if (calculatedObs == null)
                calculatedObs = createObs(calculatedConceptName, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
            calculatedObs.setValue(edd)
            return
        } else {
            def calculatedObs = find(calculatedConceptName, observations, null)
            if (hasValue(calculatedObs)) {
                voidObs(calculatedObs)
            }
        }
    }
        private
    static void setANCEDD(Collection<BahmniObservation> observations, BahmniEncounterTransaction bahmniEncounterTransaction) {
        BahmniObservation ancObservation = find("ANC, Last menstrual period", observations, null)
            BahmniObservation parent = null;
        def calculatedConceptNameanc = "ANC, Estimated Date of Delivery"
        if (hasValue(ancObservation)) {
            parent = obsParent(ancObservation, null)
            def calculatedObs = find(calculatedConceptNameanc, observations, null)

            Date obsDatetime = getDate(ancObservation)

            LocalDate edd = new LocalDate(ancObservation.getValue()).plusMonths(9).plusWeeks(1)
            if (calculatedObs == null)
                calculatedObs = createObs(calculatedConceptNameanc, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
            calculatedObs.setValue(edd)
        } else {
            def calculatedObs = find(calculatedConceptNameanc, observations, null)
            if (hasValue(calculatedObs)) {
                voidObs(calculatedObs)
            }
        }
    }


        private
    static void setWaistHipRatio(Collection<BahmniObservation> observations, BahmniEncounterTransaction bahmniEncounterTransaction) {
        BahmniObservation parent
        BahmniObservation waistCircumferenceObservation = find("Waist Circumference", observations, null)
        BahmniObservation hipCircumferenceObservation = find("Hip Circumference", observations, null)
        if (hasValue(waistCircumferenceObservation) && hasValue(hipCircumferenceObservation)) {
            def calculatedConceptName = "Waist/Hip Ratio"
            BahmniObservation calculatedObs = find(calculatedConceptName, observations, null)
            parent = obsParent(waistCircumferenceObservation, null)

            Date obsDatetime = getDate(waistCircumferenceObservation)
            def waistCircumference = waistCircumferenceObservation.getValue() as Double
            def hipCircumference = hipCircumferenceObservation.getValue() as Double
            def waistByHipRatio = (waistCircumference / hipCircumference)/1
            if (calculatedObs == null)
                calculatedObs = createObs(calculatedConceptName, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation

            calculatedObs.setValue(waistByHipRatio)
        }
    }
     
private
    static void setAutisticHyperactivityTotal(Collection<BahmniObservation> observations, BahmniEncounterTransaction bahmniEncounterTransaction) {

	BahmniObservation parent = null;
        Date obsDatetime = null;

	BahmniObservation observation1 = find("Often Fidgets or squirms in seat", observations, null)
	BahmniObservation observation2 = find("Has difficulty remaining seated", observations, null)
	BahmniObservation observation3 = find("Cannot sit still, restless or hyperactive", observations, null)
	BahmniObservation observation4 = find("Boundless energy, difficulty going to sleep.", observations, null)
	BahmniObservation observation5 = find("Clumsiness, poor coordination, unusual body movements or posturing", observations, null)
	BahmniObservation observation6 = find("Has difficulty awaiting turn in groups", observations, null)
	BahmniObservation observation7 = find("Has difficulty in following instructions", observations, null)
	BahmniObservation observation8 = find("Has difficulty sustaining to tasks", observations, null)
	BahmniObservation observation9 = find("Often engagesin physically dangerous activities without considering consequences", observations, null)
	BahmniObservation observation10 = find("Indulges in excessive screaming / crying", observations, null)

	BahmniObservation observation11 = find("Throws temper tantrums", observations, null)
	BahmniObservation observation12 = find("Destructive behaviour",observations, null)
	BahmniObservation observation13 = find("Self injurious behaviour", observations, null)
	BahmniObservation observation14 = find("Stereoyed motor mannerism Eg: flapping, spinning", observations, null)
	BahmniObservation observation15 = find("Object fixation Eg: wrappers", observations, null)
	BahmniObservation observation16 = find("Inflexible addherence to specific non functional routine or rituals", observations, null)
	BahmniObservation observation17 = find("Impulsive acts", observations, null)
	BahmniObservation observation18 = find("Obsessive speech", observations, null)
	BahmniObservation observation19 = find("Often agitated", observations, null)


		def calculatedConceptNameTotalCount = "Total score of Autistic Hyperactivity Scale"
                BahmniObservation calculatedObsCount = find(calculatedConceptNameTotalCount, observations, null)

                def calculatedConceptNameNotAtAll = "Not at all (0) - Count"
                BahmniObservation calculatedObsNotAtAll = find(calculatedConceptNameNotAtAll, observations, null)

		def calculatedConceptNameNotAtAllScore = "Not at all (0) - Score"
                BahmniObservation calculatedObsNotAtAllScore = find(calculatedConceptNameNotAtAllScore, observations, null)

                 def calculatedConceptNameJustLittle = "Just little (1) - Count"
                BahmniObservation calculatedObsJustLittle = find(calculatedConceptNameJustLittle , observations, null)

		 def calculatedConceptNameJustLittleScore = "Just little (1) - Score"
                BahmniObservation calculatedObsJustLittleScore = find(calculatedConceptNameJustLittleScore , observations, null)

                def calculatedConceptNamePrettyMuch = "Pretty much (2) - Count"
                BahmniObservation calculatedObsPrettyMuch = find(calculatedConceptNamePrettyMuch, observations, null)

		def calculatedConceptNamePrettyMuchScore = "Pretty much (2) - Score"
                BahmniObservation calculatedObsPrettyMuchScore = find(calculatedConceptNamePrettyMuchScore, observations, null)

                def calculatedConceptNameVeryMuch = "Very much (3) - Count"
                BahmniObservation calculatedObsVeryMuch= find(calculatedConceptNameVeryMuch, observations, null)

		 def calculatedConceptNameVeryMuchScore = "Very much (3) - Score"
                BahmniObservation calculatedObsVeryMuchScore = find(calculatedConceptNameVeryMuchScore, observations, null)


	
        def countNotAtAll=0, countJustLittle=0, countPrettyMuch=0, countVeryMuch = 0;

        def observationsArray = [observation1,observation2,observation3,observation4 ,observation5 ,observation6 ,observation7 
					,observation8 ,observation9 ,observation10 ,observation11 ,observation12,observation13 ,observation14 
					,observation15 ,observation16 ,observation17,observation18 ,observation19];
    			
		for (BahmniObservation obs : observationsArray) {
			if(hasValue(obs)){
				def value = null
				if (obs.getValue().name instanceof String) {
 					  value = obs.getValue().name 
				}
				else{
  					value = obs.getValue().name.display
				}
			

			if(value == 'Not at all')
				countNotAtAll= countNotAtAll + 1;
		
			 if(value =='Just little')
                              countJustLittle = countJustLittle+1;

			 if(value == 'Pretty much')
                               countPrettyMuch= countPrettyMuch+1;
			
			 if(value == 'Very much')
                                 countVeryMuch = countVeryMuch+1;
			}
		}			
		
		if(hasValue(observation1)){
		parent = obsParent(observation1, null)
		obsDatetime = getDate(observation1)

		def total = countJustLittle +  (countPrettyMuch*2) + (countVeryMuch*3) ;

		 def file1 = new File(OpenmrsUtil.getApplicationDataDirectory() + "obscalculator/groovy_debug.txt") 
		file1.append 'calculatedObsCount'+total
		file1.append 'Not at all'+countNotAtAll 	
		
                calculatedObsCount = 
			calculatedObsCount ?: createObs(calculatedConceptNameTotalCount, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
		calculatedObsCount.setValue(total)
		calculatedObsCount.setFormFieldPath('Autistic Hyperactivity Scale.47/83-0');
                calculatedObsCount.setFormNamespace('Bahmni');
  

		calculatedObsNotAtAll =
			calculatedObsNotAtAll ?: createObs(calculatedConceptNameNotAtAll,parent , bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsNotAtAll.setValue(countNotAtAll)
		calculatedObsNotAtAll.setFormFieldPath('Autistic Hyperactivity Scale.47/85-0');
                calculatedObsNotAtAll.setFormNamespace('Bahmni');

		calculatedObsNotAtAllScore =
                calculatedObsNotAtAllScore ?: createObs(calculatedConceptNameNotAtAllScore, parent , bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsNotAtAllScore.setValue(countNotAtAll*0)
                calculatedObsNotAtAllScore.setFormFieldPath('Autistic Hyperactivity Scale.47/86-0');
                calculatedObsNotAtAllScore.setFormNamespace('Bahmni');

                calculatedObsJustLittle =
			calculatedObsJustLittle ?: createObs(calculatedConceptNameJustLittle, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsJustLittle.setValue(countJustLittle)
		calculatedObsJustLittle.setFormFieldPath('Autistic Hyperactivity Scale.47/87-0');
                calculatedObsJustLittle.setFormNamespace('Bahmni');

		calculatedObsJustLittleScore =
                calculatedObsJustLittleScore ?: createObs(calculatedConceptNameJustLittleScore, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsJustLittleScore.setValue(countJustLittle*1)
                calculatedObsJustLittleScore.setFormFieldPath('Autistic Hyperactivity Scale.47/88-0');
                calculatedObsJustLittleScore.setFormNamespace('Bahmni');
		
                calculatedObsPrettyMuch = 
			calculatedObsPrettyMuch ?:createObs(calculatedConceptNamePrettyMuch, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsPrettyMuch.setValue(countPrettyMuch)
		calculatedObsPrettyMuch.setFormFieldPath('Autistic Hyperactivity Scale.47/89-0');
                calculatedObsPrettyMuch.setFormNamespace('Bahmni');

		calculatedObsPrettyMuchScore =
                calculatedObsPrettyMuchScore ?:createObs(calculatedConceptNamePrettyMuchScore, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsPrettyMuchScore.setValue(countPrettyMuch*2)
                calculatedObsPrettyMuchScore.setFormFieldPath('Autistic Hyperactivity Scale.47/90-0');
                calculatedObsPrettyMuchScore.setFormNamespace('Bahmni');

		 calculatedObsVeryMuch =
			 calculatedObsVeryMuch ?: createObs(calculatedConceptNameVeryMuch,parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
             	calculatedObsVeryMuch.setValue(countVeryMuch)
		calculatedObsVeryMuch.setFormFieldPath('Autistic Hyperactivity Scale.47/91-0');
                calculatedObsVeryMuch.setFormNamespace('Bahmni');
	
		 calculatedObsVeryMuchScore =
                 calculatedObsVeryMuchScore ?: createObs(calculatedConceptNameVeryMuchScore ,parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsVeryMuchScore.setValue(countVeryMuch*3)
                calculatedObsVeryMuchScore.setFormFieldPath('Autistic Hyperactivity Scale.47/92-0');
                calculatedObsVeryMuchScore.setFormNamespace('Bahmni');

		return
		}
    }

private static void setUncivilizedBehaviourChecklistTotal(Collection<BahmniObservation> observations, BahmniEncounterTransaction bahmniEncounterTransaction) {

        BahmniObservation parent = null;
        Date obsDatetime = null;

        BahmniObservation observation1 = find("Argues with adult", observations, null)
        BahmniObservation observation2 = find("Losses temper", observations, null)
        BahmniObservation observation3 = find("Actively defies or refuses to comply with adult’s requests or rules",observations, null)
        BahmniObservation observation4 = find("Is angry or resentful", observations, null)
        BahmniObservation observation5 = find("Bullies, threatens or intimidates others", observations, null)
        BahmniObservation observation6 = find("Is spiteful and vindictive", observations, null)
        BahmniObservation observation7 = find("Blames others for his or her mistakes or misbehaviours", observations, null)
        BahmniObservation observation8 = find("Initiates physical fights", observations, null)
        BahmniObservation observation9 = find("Lies to obtain goods for favors or to avoid obligations (eg,“cons” others)", observations, null)
        
	BahmniObservation observation10 = find("Is physically cruel to people", observations, null)
        BahmniObservation observation11 = find("Has stolen items that have value", observations, null)
        BahmniObservation observation12 = find("Deliberately destroys others’ property",observations, null)
        BahmniObservation observation13 = find("Deliberately annoys people", observations, null)
        BahmniObservation observation14 = find("Is touchy or easily annoyed by others", observations, null)
        BahmniObservation observation15 = find("Starts physical fights", observations, null)
        BahmniObservation observation16 = find("Is truant from school (skips school) without permission", observations, null)
        BahmniObservation observation17 = find("Breaks rules at school", observations, null)
        BahmniObservation observation18 = find("Has used a weapon that can cause serious harm (bat, knife, brick, gun)", observations, null)
        BahmniObservation observation19 = find("Is physically cruel to animals", observations, null)

	BahmniObservation observation20 = find("Has deliberately set fires to cause damage", observations, null)
	BahmniObservation observation21 = find("Has destroyed things that belong to his family or others", observations, null)
	BahmniObservation observation22 = find("Has stayed out at night without permission", observations, null)
	BahmniObservation observation23 = find("Has run away from home overnight", observations, null)
	BahmniObservation observation24 = find("Has forced someone into sexual activity", observations, null)
	BahmniObservation observation25 = find("Bragging, Boasting", observations, null)
	BahmniObservation observation26 = find("Deliberately harms self", observations, null)
	BahmniObservation observation27 = find("Disobedient at school", observations, null)
	BahmniObservation observation28 = find("Use Obscene language", observations, null)
        BahmniObservation observation29 = find("Lewd gestures/ comments", observations, null)

        BahmniObservation observation30 = find("Lack of empathy and concern for others", observations, null)
        BahmniObservation observation31 = find("Lack of guilt", observations, null)
        BahmniObservation observation32 = find("Lack of interest in studies", observations, null)
        BahmniObservation observation33 = find("Drugs, smokes, drinks alcohols", observations, null)
        BahmniObservation observation34 = find("Impulsive or acts without thinking", observations, null)
        BahmniObservation observation35 = find("Has run away from home overnight and has stayed out at night", observations, null)

		def calculatedConceptNameTotalValue = "Total value of Uncivilized Behaviour Checklist"
                BahmniObservation calculatedObsCount = find(calculatedConceptNameTotalValue, observations, null)

                def calculatedConceptNameNotAtAll = "Not at all - Count"
                BahmniObservation calculatedObsNotAtAll = find(calculatedConceptNameNotAtAll, observations, null)

		def calculatedConceptNameNotAtAllScore = "Not at all - Score"
                BahmniObservation calculatedObsNotAtAllScore = find(calculatedConceptNameNotAtAllScore, observations, null)

                 def calculatedConceptNameMild = "MIld (1) - Count"
                BahmniObservation calculatedObsMild = find(calculatedConceptNameMild , observations, null)

		 def calculatedConceptNameMildScore = "Mild (1) - Score"
                BahmniObservation calculatedObsMildScore = find(calculatedConceptNameMildScore , observations, null)

                def calculatedConceptNameModerate = "Moderate (2) - Count"
                BahmniObservation calculatedObsModerate = find(calculatedConceptNameModerate, observations, null)

		def calculatedConceptNameModerateScore = "Moderate (2) - Score"
                BahmniObservation calculatedObsModerateScore = find(calculatedConceptNameModerateScore, observations, null)

                def calculatedConceptNameExtreme = "Extreme (3) - Count"
                BahmniObservation calculatedObsExtreme= find(calculatedConceptNameExtreme, observations, null)

		def calculatedConceptNameExtremeScore = "Extreme (3) - Score"
                BahmniObservation calculatedObsExtremeScore = find(calculatedConceptNameExtremeScore, observations, null)



        def countNotAtAll=0, countMild=0, countModerate=0, countExtreme = 0;

        def observationsArray = [observation1,observation2,observation3,observation4 ,observation5 ,observation6 ,observation7
                                        ,observation8,observation9 ,observation10 ,observation11 ,observation12,observation13
					,observation14,observation15 ,observation16 ,observation17,observation18 ,observation19
					,observation20,observation21,observation22,observation23,observation24,observation25
					,observation26,observation27,observation28,observation29,observation30,observation31
					,observation32,observation33,observation34,observation35];

    for (BahmniObservation obs : observationsArray) {
                        if(hasValue(obs)){
                                def value = null
                                if (obs.getValue().name instanceof String) {
                                          value = obs.getValue().name
                                }
                                else{
                                        value = obs.getValue().name.display
                                }


                        if(value == 'Not at all')
                                countNotAtAll= countNotAtAll + 1;

                         if(value =='Mild')
                              countMild = countMild+1;

                         if(value == 'Moderate')
                               countModerate= countModerate+1;

                         if(value == 'Extreme')
                                 countExtreme = countExtreme+1;
                        }
                }

		if(hasValue(observation1)){
                parent = obsParent(observation1, null)
                obsDatetime = getDate(observation1)

                def total = countMild +  (countModerate*2) + (countExtreme*3) ;

                 calculatedObsCount =
                        calculatedObsCount ?: createObs(calculatedConceptNameTotalValue,parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsCount.setValue(total)
                calculatedObsCount.setFormFieldPath('Uncivilized Behaviour Checklist.14/49-0');
                calculatedObsCount.setFormNamespace('Bahmni');

		 calculatedObsNotAtAll =
                        calculatedObsNotAtAll ?: createObs(calculatedConceptNameNotAtAll,parent , bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsNotAtAll.setValue(countNotAtAll)
                calculatedObsNotAtAll.setFormFieldPath('Uncivilized Behaviour Checklist.14/65-0');
                calculatedObsNotAtAll.setFormNamespace('Bahmni');

		calculatedObsNotAtAllScore =
                calculatedObsNotAtAllScore ?: createObs(calculatedConceptNameNotAtAllScore, parent , bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsNotAtAllScore.setValue(countNotAtAll*0)
                calculatedObsNotAtAllScore.setFormFieldPath('Uncivilized Behaviour Checklist.14/66-0');
                calculatedObsNotAtAllScore.setFormNamespace('Bahmni');


                calculatedObsMild =
                        calculatedObsMild ?: createObs(calculatedConceptNameMild , parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsMild.setValue(countMild)
                calculatedObsMild.setFormFieldPath('Uncivilized Behaviour Checklist.14/58-0');
                calculatedObsMild.setFormNamespace('Bahmni');

		calculatedObsMildScore =
                calculatedObsMildScore ?: createObs(calculatedConceptNameMildScore , parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsMildScore.setValue(countMild*1)
                calculatedObsMildScore.setFormFieldPath('Uncivilized Behaviour Checklist.14/59-0');
                calculatedObsMildScore.setFormNamespace('Bahmni');

                calculatedObsModerate =
                        calculatedObsModerate ?:createObs(calculatedConceptNameModerate, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsModerate.setValue(countModerate)
                calculatedObsModerate.setFormFieldPath('Uncivilized Behaviour Checklist.14/61-0');
                calculatedObsModerate.setFormNamespace('Bahmni');

		calculatedObsModerateScore =
                calculatedObsModerateScore ?:createObs(calculatedConceptNameModerateScore, parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsModerateScore.setValue(countModerate*2)
                calculatedObsModerateScore.setFormFieldPath('Uncivilized Behaviour Checklist.14/62-0');
                calculatedObsModerateScore.setFormNamespace('Bahmni');

                 calculatedObsExtreme =
                         calculatedObsExtreme ?: createObs(calculatedConceptNameExtreme ,parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsExtreme.setValue(countExtreme)
                calculatedObsExtreme.setFormFieldPath('Uncivilized Behaviour Checklist.14/63-0');
                calculatedObsExtreme.setFormNamespace('Bahmni');

		calculatedObsExtremeScore =
                calculatedObsExtremeScore ?: createObs(calculatedConceptNameExtremeScore ,parent, bahmniEncounterTransaction, obsDatetime) as BahmniObservation
                calculatedObsExtremeScore.setValue(countExtreme*3)
                calculatedObsExtremeScore.setFormFieldPath('Uncivilized Behaviour Checklist.14/64-0');
                calculatedObsExtremeScore.setFormNamespace('Bahmni');

		return
		}


}


    private static BahmniObservation obsParent(BahmniObservation child, BahmniObservation parent) {
        if (parent != null) return parent;

        if(child != null) {	
            return obsParentMap.get(child)
        }
    }

    private static Date getDate(BahmniObservation observation) {
        return hasValue(observation) && !observation.voided ? observation.getObservationDateTime() : null;
    }

    private static boolean isSameObs(BahmniObservation observation, Obs editedObs) {
        if(observation && editedObs) {
            return  (editedObs.uuid == observation.encounterTransactionObservation.uuid && editedObs.valueNumeric == observation.value);
        } else if(observation == null && editedObs == null) {
            return true;
        }
        return false;
    }

    private static boolean hasValue(BahmniObservation observation) {
        return observation != null && observation.getValue() != null && !StringUtils.isEmpty(observation.getValue().toString());
    }

    private static void voidObs(BahmniObservation bmiObservation) {
        if (hasValue(bmiObservation)) {
            bmiObservation.voided = true
        }
    }

    private static void voidPreviousBMIObs(Set<Obs> bmiObs) {
        if(bmiObs) {
            bmiObs.each { Obs obs ->
                Concept concept = Context.getConceptService().getConceptByUuid(obs.getConcept().uuid);
                if (concept.getName().name.equalsIgnoreCase("BMI Data") || concept.getName().name.equalsIgnoreCase("BMI") ||
                        concept.getName().name.equalsIgnoreCase("BMI ABNORMAL") || concept.getName().name.equalsIgnoreCase("BMI Status Data")
                        || concept.getName().name.equalsIgnoreCase("BMI STATUS") || concept.getName().name.equalsIgnoreCase("BMI STATUS ABNORMAL")) {

                    obs.voided = true;
                    obs.setVoidReason("Replaced with a new one because it was changed");
                    Context.getObsService().saveObs(obs, "Replaced with a new one because it was changed");
                }
            }
        }
    }

    static BahmniObservation createObs(String conceptName, BahmniObservation parent, BahmniEncounterTransaction encounterTransaction, Date obsDatetime) {
        def concept = Context.getConceptService().getConceptByName(conceptName)
        BahmniObservation newObservation = new BahmniObservation()
        newObservation.setConcept(new EncounterTransaction.Concept(concept.getUuid(), conceptName))
        newObservation.setObservationDateTime(obsDatetime);
        parent == null ? encounterTransaction.addObservation(newObservation) : parent.addGroupMember(newObservation)
        return newObservation
    }

    static def bmi(Double height, Double weight) {
        if (height == ZERO) {
            throw new IllegalArgumentException("Please enter Height greater than zero")
        } else if (weight == ZERO) {
            throw new IllegalArgumentException("Please enter Weight greater than zero")
        }
        Double heightInMeters = height / 100;
        Double value = weight / (heightInMeters * heightInMeters);
        return new BigDecimal(value).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
    };

    static def bmiStatus(Double bmi, Integer ageInMonth, String gender) {
        BMIChart bmiChart = readCSV(OpenmrsUtil.getApplicationDataDirectory() + "obscalculator/BMI_chart.csv");
        def bmiChartLine = bmiChart.get(gender, ageInMonth);
        if(bmiChartLine != null ) {
            return bmiChartLine.getStatus(bmi);
        }

        if (bmi < BMI_VERY_SEVERELY_UNDERWEIGHT) {
            return BmiStatus.VERY_SEVERELY_UNDERWEIGHT;
        }
        if (bmi < BMI_SEVERELY_UNDERWEIGHT) {
            return BmiStatus.SEVERELY_UNDERWEIGHT;
        }
        if (bmi < BMI_UNDERWEIGHT) {
            return BmiStatus.UNDERWEIGHT;
        }
        if (bmi < BMI_NORMAL) {
            return BmiStatus.NORMAL;
        }
        if (bmi < BMI_OVERWEIGHT) {
            return BmiStatus.OVERWEIGHT;
        }
        if (bmi < BMI_OBESE) {
            return BmiStatus.OBESE;
        }
        if (bmi < BMI_SEVERELY_OBESE) {
            return BmiStatus.SEVERELY_OBESE;
        }
        if (bmi >= BMI_SEVERELY_OBESE) {
            return BmiStatus.VERY_SEVERELY_OBESE;
        }
        return null
    }

    static def bmiAbnormal(BmiStatus status) {
        return status != BmiStatus.NORMAL;
    };

    static Double fetchLatestValue(String conceptName, String patientUuid, BahmniObservation excludeObs, Date tillDate) {
        SessionFactory sessionFactory = Context.getRegisteredComponents(SessionFactory.class).get(0)
        def excludedObsIsSaved = excludeObs != null && excludeObs.uuid != null
        String excludeObsClause = excludedObsIsSaved ? " and obs.uuid != :excludeObsUuid" : ""
        Query queryToGetObservations = sessionFactory.getCurrentSession()
                .createQuery("select obs " +
                " from Obs as obs, ConceptName as cn " +
                " where obs.person.uuid = :patientUuid " +
                " and cn.concept = obs.concept.conceptId " +
                " and cn.name = :conceptName " +
                " and obs.voided = false" +
                " and obs.obsDatetime <= :till" +
                excludeObsClause +
                " order by obs.obsDatetime desc ");
        queryToGetObservations.setString("patientUuid", patientUuid);
        queryToGetObservations.setParameterList("conceptName", conceptName);
        queryToGetObservations.setParameter("till", tillDate);
        if (excludedObsIsSaved) {
            queryToGetObservations.setString("excludeObsUuid", excludeObs.uuid)
        }
        queryToGetObservations.setMaxResults(1);
        List<Obs> observations = queryToGetObservations.list();
        if (observations.size() > 0) {
            return observations.get(0).getValueNumeric();
        }
        return null
    }

    static BahmniObservation find(String conceptName, Collection<BahmniObservation> observations, BahmniObservation parent) {
        for (BahmniObservation observation : observations) {
            if (conceptName.equalsIgnoreCase(observation.getConcept().getName())) {
                obsParentMap.put(observation, parent);       
                return observation;
            }
            BahmniObservation matchingObservation = find(conceptName, observation.getGroupMembers(), observation)
            if (matchingObservation) return matchingObservation;
        }
        return null
    }

    static BMIChart readCSV(String fileName) {
        def chart = new BMIChart();
        try {
            new File(fileName).withReader { reader ->
                def header = reader.readLine();
                reader.splitEachLine(",") { tokens ->
                    chart.add(new BMIChartLine(tokens[0], tokens[1], tokens[2], tokens[3], tokens[4], tokens[5]));
                }
            }
        } catch (FileNotFoundException e) {
        }
        return chart;
    }

    static class BMIChartLine {
        public String gender;
        public Integer ageInMonth;
        public Double third;
        public Double fifteenth;
        public Double eightyFifth;
        public Double ninetySeventh;

        BMIChartLine(String gender, String ageInMonth, String third, String fifteenth, String eightyFifth, String ninetySeventh) {
            this.gender = gender
            this.ageInMonth = ageInMonth.toInteger();
            this.third = third.toDouble();
            this.fifteenth = fifteenth.toDouble();
            this.eightyFifth = eightyFifth.toDouble();
            this.ninetySeventh = ninetySeventh.toDouble();
        }

        public BmiStatus getStatus(Double bmi) {
            if(bmi < third) {
                return BmiStatus.SEVERELY_UNDERWEIGHT
            } else if(bmi < fifteenth) {
                return BmiStatus.UNDERWEIGHT
            } else if(bmi < eightyFifth) {
                return BmiStatus.NORMAL
            } else if(bmi < ninetySeventh) {
                return BmiStatus.OVERWEIGHT
            } else {
                return BmiStatus.OBESE
            }
        }
    }

    static class BMIChart {
        List<BMIChartLine> lines;
        Map<BMIChartLineKey, BMIChartLine> map = new HashMap<BMIChartLineKey, BMIChartLine>();

        public add(BMIChartLine line) {
            def key = new BMIChartLineKey(line.gender, line.ageInMonth);
            map.put(key, line);
        }

        public BMIChartLine get(String gender, Integer ageInMonth) {
            def key = new BMIChartLineKey(gender, ageInMonth);
            return map.get(key);
        }
    }

    static class BMIChartLineKey {
        public String gender;
        public Integer ageInMonth;

        BMIChartLineKey(String gender, Integer ageInMonth) {
            this.gender = gender
            this.ageInMonth = ageInMonth
        }

        boolean equals(o) {
            if (this.is(o)) return true
            if (getClass() != o.class) return false

            BMIChartLineKey bmiKey = (BMIChartLineKey) o

            if (ageInMonth != bmiKey.ageInMonth) return false
            if (gender != bmiKey.gender) return false

            return true
        }

        int hashCode() {
            int result
            result = (gender != null ? gender.hashCode() : 0)
            result = 31 * result + (ageInMonth != null ? ageInMonth.hashCode() : 0)
            return result
        }
    }
}

