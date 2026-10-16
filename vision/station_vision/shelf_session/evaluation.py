"""Small source-inspection reference; never equate self scores with accuracy."""


def temporal_iou(a,b):
    overlap=max(0,min(a['end_ms'],b['end_ms'])-max(a['start_ms'],b['start_ms']))
    union=max(a['end_ms'],b['end_ms'])-min(a['start_ms'],b['start_ms'])
    return overlap/max(union,1)


def evaluate(reference,sessions,results,controls):
    used=set();matches=[];false=[]
    for s in sessions:
        options=[(temporal_iou(s,g),i,g) for i,g in enumerate(reference['sessions'])
                 if i not in used and g['person_id']==s['person_id']]
        best=max(options,key=lambda x:x[0]) if options else None
        if not best or best[0]<.3:false.append(s['id']);continue
        score,index,g=best;used.add(index)
        matches.append({'session_id':s['id'],'reference_id':g['id'],'temporal_iou':round(score,4),
            'start_error_ms':round(s['start_ms']-g['start_ms'],3),'end_error_ms':round(s['end_ms']-g['end_ms'],3),
            'algorithm_shelf_level':s['shelf_level'],'reference_shelf_level':g['shelf_level'],
            'shelf_level_correct':s['shelf_level']==g['shelf_level']})
    decisions=[]
    for s in sessions:
        present=[g for g in reference['sessions'] if temporal_iou(s,g)>.3]
        expected=any(any(s['start_ms']<=t<=s['end_ms'] for t in g['pickup_times_ms']) for g in present)
        result=results.get(s['id'],{});op=result.get('opinion') or {}
        predicted=op.get('pickup_detected')
        decisions.append({'id':s['id'],'kind':'generated_session','expected_pickup':expected,
            'predicted_pickup':predicted,'correct':predicted==expected if type(predicted) is bool else None,
            'algorithm_shelf_level_correct':s['shelf_level']==present[0]['shelf_level'] if present else None,
            'model_shelf_level':op.get('model_shelf_level',op.get('shelf_level')),
            'model_shelf_level_correct':op.get('model_shelf_level',op.get('shelf_level'))==present[0]['shelf_level'] if present else None,
            'final_shelf_level':op.get('shelf_level'),
            'final_shelf_level_correct':op.get('shelf_level')==present[0]['shelf_level'] if present else None})
    for c in controls:
        op=(c.get('review') or {}).get('opinion') or {};predicted=op.get('pickup_detected')
        expected=c['session']['expected_pickup']
        decisions.append({'id':c['session']['id'],'kind':'constructed_negative_control','expected_pickup':expected,
            'predicted_pickup':predicted,'correct':predicted==expected if type(predicted) is bool else None})
    known=[x for x in decisions if x['correct'] is not None]
    correct=sum(x['correct'] for x in known)
    positives=[x for x in decisions if x['expected_pickup']]
    negatives=[x for x in decisions if not x['expected_pickup']]
    counts={'positive_cases':len(positives),'positive_correct':sum(x['predicted_pickup'] is True for x in positives),
        'positive_unknown':sum(x['predicted_pickup'] is None for x in positives),
        'negative_cases':len(negatives),'negative_correct':sum(x['predicted_pickup'] is False for x in negatives),
        'negative_false_positive':sum(x['predicted_pickup'] is True for x in negatives)}
    return {'reference_provenance':reference['provenance'],
        'independent_team_ground_truth':bool(reference.get('independently_human_verified',False)),
        'reference_layer_adjudication':reference.get('layer_adjudication'),
        'video_count':1,'reference_sessions':len(reference['sessions']),'generated_sessions':len(sessions),
        'matched_sessions':len(matches),'unmatched_session_ids':false,
        'missed_reference_sessions':len(reference['sessions'])-len(used),'boundary_matches':matches,
        'review_cases':decisions,'decidable_cases':len(known),'unknown_or_failed_cases':len(decisions)-len(known),
        'pickup_case_counts':counts,
        'control_inputs_sanitized':all(c.get('evaluation_input_sanitized',False) for c in controls) if controls else None,
        'correct_decidable_cases':correct,'accuracy_on_decidable_cases':correct/len(known) if known else None,
        'correct_fraction_including_abstentions':correct/len(decisions) if decisions else None,
        'review_coverage':len(known)/len(decisions) if decisions else None,
        'generalization_accuracy_established':False,'pickup_score_calibrated':False,
        'note':'One visually inspected video plus constructed controls; not a benchmark. Ground truth concerns the full interval, not only five selected images.'}
