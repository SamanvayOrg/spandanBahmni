select concept_name.name , count(*) 
 from person_attribute 
inner join concept on person_attribute.value = concept.concept_id 
inner join concept_name on concept_name.concept_id = concept.concept_id
 where person_attribute_type_id = 32  and concept_name.name In ( 'Above 1.6 lakhs','Between 85k - 1.6lakhs','Family Income upto 85k')  
 group by concept_name.name;
