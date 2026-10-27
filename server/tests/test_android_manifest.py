"""Wire shapes produced by AnalysisInputs.request; no Room-only sourceRef is sent."""
import copy

import pytest
from pydantic import ValidationError

from app.evidence import EvidenceIndex
from app.errors import ServiceError
from app.schemas import AnalysisRequest, validate_candidate


def android_request(event_count=125):
    inputs = [{"inputId": "tr-42", "kind": "TRANSCRIPT", "revision": 2, "assetId": None,
               "payload": {"schemaVersion": 1, "timingSource": "bailian_timestamp_v1", "segments": [
                   {"segmentId": "seg-1", "mediaStartMs": 0, "mediaEndMs": 1000, "text": "先介绍二叉树。",
                    "speakerRole": "TEACHER", "roleSource": "MANUAL",
                    "classRanges": [{"startMs": 0, "endMs": 1000}], "mappingQuality": "EXACT"},
                   {"segmentId": "seg-2", "mediaStartMs": 1000, "mediaEndMs": 2000, "text": "请讨论遍历结果。",
                    "speakerRole": "UNKNOWN", "roleSource": "UNKNOWN",
                    "classRanges": [{"startMs": 1000, "endMs": 2000}], "mappingQuality": "EXACT"}]}},
              {"inputId": "course-7", "kind": "COURSE_REFERENCE", "revision": 1, "assetId": None,
               "payload": {"knowledgePoints": [{"id": "kp-1", "text": "二叉树遍历"}],
                           "objectives": [{"id": "go-1", "text": "解释遍历结果"}],
                           "selectedKnowledgePointIds": ["kp-1"], "selectedObjectiveIds": ["go-1"]}},
              {"inputId": "reviewed-9", "kind": "BOPPPS", "revision": 1, "assetId": None,
               "payload": {"segments": [
                   {"id": "bs1_range_0", "label": "介绍", "stage": "BRIDGE_IN", "startMs": 0, "endMs": 1000,
                    "evidenceRefs": [{"inputId": "tr-42", "itemKey": "seg-1", "quote": "二叉树"}]},
                   {"id": "bs2_range_0", "label": "讨论", "stage": "PARTICIPATORY_LEARNING", "startMs": 1000, "endMs": 2000,
                    "evidenceRefs": [{"inputId": "tr-42", "itemKey": "seg-2", "quote": "讨论"}]}]}},
              {"inputId": "context", "kind": "CONTEXT", "revision": 1, "assetId": None,
               "payload": {"context": {"classDurationMs": 2000, "allAudioTranscribed": True, "missingAudioIds": [],
                                       "recordingSpans": [{"classStartMs": 0, "classEndMs": 2000, "mappingQuality": "EXACT"}]}}}]
    inputs += [{"inputId": f"event-{i}", "kind": "INTERACTION_EVENT", "revision": 1, "assetId": None,
                "payload": {"events": [{"id": f"event-{i}", "type": "TEACHER_QUESTION", "text": "提问遍历结果", "atMs": i * 10}]}}
               for i in range(event_count)]
    return {"schemaVersion": 1, "caseId": "4f48f559-6833-4c5b-a845-d7ac3ff32294", "operation": "TRANSITION",
            "inputFingerprint": "a" * 64, "methodVersion": "transition_v1", "inputs": inputs}


def test_android_mapped_manual_transcript_and_many_events_match_wire_contract():
    body = android_request()
    assert len(body["inputs"]) > 100
    index = EvidenceIndex(AnalysisRequest.model_validate(body))
    assert index.missing_requirements() is None
    assert index.coverage_sufficient()
    candidate = validate_candidate("TRANSITION", {"resultState": "SUPPORTED", "limitations": [], "payload": {"transitions": [{
        "fromSegmentId": "bs1_range_0", "toSegmentId": "bs2_range_0", "type": "NO_OBSERVED_LINK",
        "statement": "候选衔接需审核", "reason": "根据相邻真实课堂片段", "evidenceRefs": [
            {"inputId": "tr-42", "itemKey": "seg-1", "quote": "二叉树"},
            {"inputId": "tr-42", "itemKey": "seg-2", "quote": "讨论"}]}]}})
    index.validate_result(candidate["payload"])
    assert len(AnalysisRequest.model_validate(android_request(996)).inputs) == 1000
    with pytest.raises(ValidationError):
        AnalysisRequest.model_validate(android_request(997))
    leaked = copy.deepcopy(body)
    leaked["inputs"][0]["sourceRef"] = {"rowId": 42}
    with pytest.raises(ValidationError):
        AnalysisRequest.model_validate(leaked)


def test_android_context_is_coverage_only_and_never_citable_evidence():
    body = android_request(0)
    body["operation"] = "CONTENT"
    index = EvidenceIndex(AnalysisRequest.model_validate(body))
    assert index.coverage_sufficient()
    result = {"topics": [{"id": "topic-1", "name": "二叉树", "keywords": [], "evidenceRefs": [
        {"inputId": "tr-42", "itemKey": "seg-1", "quote": "二叉树"},
        {"inputId": "context", "itemKey": None, "quote": None}]}]}
    with pytest.raises(ServiceError, match="白名单"):
        index.validate_result(result)


def test_android_and_server_quotes_match_one_text_field_not_joined_fields():
    body = android_request(0)
    body["inputs"][2]["payload"]["segments"][0].update(label="讲授", note="二叉树")
    index = EvidenceIndex(AnalysisRequest.model_validate(body))
    assert index.refs([{"inputId": "reviewed-9", "itemKey": "bs1_range_0", "quote": "二 叉树"}])
    with pytest.raises(ServiceError, match="引文"):
        index.refs([{"inputId": "reviewed-9", "itemKey": "bs1_range_0", "quote": "讲授二叉树"}])
