import copy

import pytest

from app.errors import ServiceError
from app.evidence import EvidenceIndex
from app.schemas import AnalysisRequest, STAGES, validate_candidate
from conftest import content_result, request_body


def manifest(operation):
    body = request_body(operation)
    body["inputs"][0]["payload"]["segments"].append({"segmentId": "seg-2", "text": "请讨论遍历结果。", "mediaStartMs": 1000, "mediaEndMs": 2000})
    for segment in body["inputs"][0]["payload"]["segments"]:
        segment.update(classRanges=[{"startMs": segment["mediaStartMs"], "endMs": segment["mediaEndMs"]}], mappingQuality="EXACT")
    body["inputs"] += [
        {"inputId": "course", "kind": "COURSE_REFERENCE", "revision": 1, "payload": {"knowledgePoints": [{"id": "KP1", "text": "二叉树遍历"}], "objectives": [{"id": "GO1", "text": "说明遍历顺序"}]}},
        {"inputId": "phases", "kind": "PHASES", "revision": 1, "payload": {"segments": [{"id": "p1", "label": "讲授", "startMs": 0, "endMs": 1000}, {"id": "p2", "label": "讨论", "startMs": 1000, "endMs": 2000}]}},
        {"inputId": "image", "kind": "IMAGE_TEXT", "revision": 1, "payload": {"blocks": [{"id": "b1", "text": "二叉树遍历", "region": None}]}},
    ]
    return AnalysisRequest.model_validate(body)


def ref(input_id="tr-1", key="seg-1", quote="二叉树"):
    return {"inputId": input_id, "itemKey": key, "quote": quote}


def test_all_semantic_operations_have_validated_shapes_and_evidence():
    payloads = {
        "CONTENT": content_result()["payload"],
        "SYLLABUS_ALIGNMENT": {"alignments": [{"knowledgePointId": "KP1", "coverage": "COVERED", "evidenceRefs": [ref()], "reason": "实际提到二叉树遍历"}], "deviations": [], "coverageScope": {"selectedKnowledgePointIds": ["KP1"], "transcriptVersionIds": ["tr-1"], "missingAudioIds": []}},
        "BOPPPS": {"coverage": [{"stage": s, "status": "OBSERVED" if s == "PARTICIPATORY_LEARNING" else "INSUFFICIENT_INPUT", "segmentIds": ["bs1"] if s == "PARTICIPATORY_LEARNING" else [], "reason": "依据输入"} for s in STAGES], "segments": [{"id": "bs1", "stage": "PARTICIPATORY_LEARNING", "label": "讨论", "evidenceRefs": [ref("tr-1", "seg-2", "讨论")]}]},
        "GOAL_ACTIVITY": {"activities": [{"id": "a1", "label": "讨论", "sourceRefs": [ref("tr-1", "seg-2", "讨论")]}], "links": [{"objectiveId": "GO1", "activityId": "a1", "supportLevel": "PARTIAL", "reason": "讨论遍历结果", "evidenceRefs": [ref("tr-1", "seg-2", "遍历结果")]}], "unsupportedGoals": []},
        "TRANSITION": {"transitions": [{"fromSegmentId": "p1", "toSegmentId": "p2", "type": "NO_OBSERVED_LINK", "statement": "候选联系待审核", "reason": "前后文本", "evidenceRefs": [ref(), ref("tr-1", "seg-2", "讨论")]}]},
        "MATERIAL_SUPPORT": {"supports": [{"imageBlockId": "b1", "topicId": None, "knowledgePointId": "KP1", "relation": "SUPPORTS", "reason": "内容相关", "evidenceRefs": [ref(), ref("image", "b1", "二叉树")]}], "concerns": []},
    }
    for operation, payload in payloads.items():
        candidate = validate_candidate(operation, {"resultState": "SUPPORTED", "payload": payload, "limitations": []})
        EvidenceIndex(manifest(operation)).validate_result(candidate["payload"])


def test_course_text_cannot_be_proof_of_teaching_and_quote_whitespace():
    index = EvidenceIndex(manifest("CONTENT"))
    candidate = content_result()["payload"]
    candidate["topics"][0]["evidenceRefs"] = [ref("course", "KP1", "二叉树")]
    with pytest.raises(ServiceError): index.validate_result(candidate)
    assert index.refs([ref(quote="二 叉树")])
    with pytest.raises(ServiceError): index.refs([ref(quote="二叉木")])
    with pytest.raises(ServiceError): index.refs([ref("other-task", "seg-1", "二叉树")])


def test_coverage_not_inferred_from_boolean():
    body = manifest("SYLLABUS_ALIGNMENT").model_dump()
    body["inputs"].append({"inputId": "context", "kind": "CONTEXT", "revision": 1, "payload": {"context": {"sufficient": True, "classDurationMs": 2000}}})
    index = EvidenceIndex(AnalysisRequest.model_validate(body))
    assert not index.coverage_sufficient()
    body["inputs"][-1]["payload"]["context"].update(allAudioTranscribed=True, missingAudioIds=[], recordingSpans=[{"classStartMs": 0, "classEndMs": 1800, "mappingQuality": "EXACT"}])
    assert EvidenceIndex(AnalysisRequest.model_validate(body)).coverage_sufficient()
    body["inputs"][-1]["payload"]["context"]["recordingSpans"][0]["mappingQuality"] = "UNKNOWN"
    assert not EvidenceIndex(AnalysisRequest.model_validate(body)).coverage_sufficient()


def test_missing_is_downgraded_and_wrong_objective_is_rejected():
    index = EvidenceIndex(manifest("SYLLABUS_ALIGNMENT"))
    payload = {"alignments": [{"knowledgePointId": "KP1", "coverage": "POSSIBLY_MISSING", "evidenceRefs": [], "reason": "未见"}], "deviations": [], "coverageScope": {"selectedKnowledgePointIds": ["KP1"], "transcriptVersionIds": ["tr-1"], "missingAudioIds": []}}
    index.validate_result(payload)
    assert payload["alignments"][0]["coverage"] == "INSUFFICIENT_INPUT"
    payload["alignments"][0]["knowledgePointId"] = "unknown"
    with pytest.raises(ServiceError): index.validate_result(payload)


def test_no_empty_supported_or_extra_output_fields():
    with pytest.raises(ValueError): validate_candidate("CONTENT", {"resultState": "SUPPORTED", "payload": {"topics": []}, "limitations": []})
    candidate = content_result()
    candidate["payload"]["topics"][0]["inventedTimestamp"] = 2
    with pytest.raises(ValueError): validate_candidate("CONTENT", candidate)


def test_image_regions_and_unreadable_text():
    candidate = {"resultState": "UNREADABLE", "payload": {"readability": "UNREADABLE", "blocks": [], "limitations": ["模糊"]}, "limitations": ["模糊"]}
    validate_candidate("IMAGE_TEXT", candidate)
    candidate["payload"]["blocks"] = [{"id": "b1", "text": "猜测", "region": None}]
    with pytest.raises(ValueError): validate_candidate("IMAGE_TEXT", candidate)


def test_invalid_input_interval_and_duplicate_key():
    body = request_body()
    body["inputs"][0]["payload"]["segments"][0]["mediaEndMs"] = -1
    with pytest.raises(ServiceError): EvidenceIndex(AnalysisRequest.model_validate(body))


def test_manual_role_source_and_malformed_context():
    body = request_body()
    body["inputs"][0]["payload"]["segments"][0].update(speakerRole="TEACHER", roleSource="MANUAL")
    EvidenceIndex(AnalysisRequest.model_validate(body))
    body["inputs"].append({"inputId": "bad", "kind": "CONTEXT", "revision": 1, "payload": {"context": []}})
    with pytest.raises(ServiceError): EvidenceIndex(AnalysisRequest.model_validate(body))


def test_transition_cannot_cite_arbitrary_or_unmapped_sentences():
    request = manifest("TRANSITION")
    payload = {"transitions": [{"fromSegmentId": "p1", "toSegmentId": "p2", "type": "NO_OBSERVED_LINK", "statement": "候选", "reason": "前后内容", "evidenceRefs": [ref(), ref("tr-1", "seg-2", "讨论")]}]}
    EvidenceIndex(request).validate_result(payload)
    request.inputs[0].payload["segments"][1]["classRanges"] = [{"startMs": 5000, "endMs": 6000}]
    with pytest.raises(ServiceError): EvidenceIndex(request).validate_result(payload)
    for segment in request.inputs[0].payload["segments"]:
        segment.update(classRanges=[], mappingQuality="UNKNOWN")
    assert "时间映射" in EvidenceIndex(request).missing_requirements()
    body = request_body()
    body["inputs"][0]["payload"]["segments"] *= 2
    with pytest.raises(ServiceError): EvidenceIndex(AnalysisRequest.model_validate(body))
