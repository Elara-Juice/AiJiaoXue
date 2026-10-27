from __future__ import annotations

import hashlib
import json
from typing import Annotated, Any, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, JsonValue, model_validator

from .errors import ServiceError

Operation = Literal["TRANSCRIBE", "IMAGE_TEXT", "CONTENT", "SYLLABUS_ALIGNMENT", "BOPPPS", "GOAL_ACTIVITY", "TRANSITION", "MATERIAL_SUPPORT"]
ResultState = Literal["SUPPORTED", "PARTIAL", "INSUFFICIENT_INPUT", "UNREADABLE"]
Stage = Literal["BRIDGE_IN", "OBJECTIVE", "PRE_ASSESSMENT", "PARTICIPATORY_LEARNING", "POST_ASSESSMENT", "SUMMARY"]
STAGES = {"BRIDGE_IN", "OBJECTIVE", "PRE_ASSESSMENT", "PARTICIPATORY_LEARNING", "POST_ASSESSMENT", "SUMMARY"}
def not_blank(value: str) -> str:
    if not value.strip(): raise ValueError("text must not be blank")
    return value


Nonempty = Annotated[str, Field(min_length=1, max_length=20000), AfterValidator(not_blank)]
Identifier = Annotated[str, Field(min_length=1, max_length=160, pattern=r"^[A-Za-z0-9_.:\-]+$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class AssetMetadata(StrictModel):
    caseId: Identifier
    inputId: Identifier
    kind: Literal["AUDIO", "IMAGE"]
    sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class InputItem(StrictModel):
    inputId: Identifier
    kind: Literal["AUDIO", "IMAGE", "TRANSCRIPT", "TRANSCRIPT_SEGMENT", "COURSE_REFERENCE", "PHASES", "EVENTS", "FACTS", "MANUAL_PHASE", "INTERACTION_EVENT", "ENGAGEMENT_RECORD", "IMAGE_TEXT", "MANUAL_FACT", "CONTENT", "BOPPPS", "CONTEXT"]
    revision: Annotated[int, Field(ge=1)]
    assetId: Identifier | None = None
    payload: dict[str, JsonValue] = Field(default_factory=dict)


class AnalysisRequest(StrictModel):
    schemaVersion: Literal[1] = 1
    caseId: Identifier
    operation: Operation
    inputFingerprint: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    methodVersion: Identifier
    inputs: Annotated[list[InputItem], Field(min_length=1, max_length=1000)]

    @model_validator(mode="after")
    def unique_inputs(self):
        if len({i.inputId for i in self.inputs}) != len(self.inputs):
            raise ValueError("duplicate inputId")
        if self.operation in {"TRANSCRIBE", "IMAGE_TEXT"}:
            expected = "AUDIO" if self.operation == "TRANSCRIBE" else "IMAGE"
            if len(self.inputs) != 1 or self.inputs[0].kind != expected or not self.inputs[0].assetId:
                raise ValueError(f"{self.operation} requires one {expected} asset; submit separate jobs per asset")
        return self


class AckRequest(StrictModel):
    resultHash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class EvidenceRef(StrictModel):
    inputId: Identifier
    itemKey: Identifier | None = None
    quote: Nonempty | None = None


Refs = Annotated[list[EvidenceRef], Field(max_length=100)]


class Topic(StrictModel):
    id: Identifier
    name: Nonempty
    keywords: Annotated[list[Nonempty], Field(max_length=50)]
    evidenceRefs: Annotated[list[EvidenceRef], Field(min_length=1, max_length=100)]


class ContentPayload(StrictModel):
    topics: Annotated[list[Topic], Field(max_length=100)]


class Alignment(StrictModel):
    knowledgePointId: Identifier
    coverage: Literal["COVERED", "POSSIBLY_MISSING", "INSUFFICIENT_INPUT"]
    evidenceRefs: Refs
    reason: Nonempty


class Deviation(StrictModel):
    id: Identifier
    statement: Nonempty
    evidenceRefs: Annotated[list[EvidenceRef], Field(min_length=1, max_length=100)]
    reason: Nonempty


class CoverageScope(StrictModel):
    selectedKnowledgePointIds: list[Identifier]
    transcriptVersionIds: list[Identifier]
    missingAudioIds: list[Identifier]


class SyllabusPayload(StrictModel):
    alignments: Annotated[list[Alignment], Field(max_length=200)]
    deviations: Annotated[list[Deviation], Field(max_length=100)]
    coverageScope: CoverageScope


class BopppsCoverage(StrictModel):
    stage: Stage
    status: Literal["OBSERVED", "POSSIBLY_MISSING", "INSUFFICIENT_INPUT"]
    segmentIds: list[Identifier]
    reason: Nonempty


class BopppsSegment(StrictModel):
    id: Identifier
    stage: Stage
    label: Nonempty
    evidenceRefs: Annotated[list[EvidenceRef], Field(min_length=1, max_length=100)]


class BopppsPayload(StrictModel):
    coverage: Annotated[list[BopppsCoverage], Field(min_length=6, max_length=6)]
    segments: Annotated[list[BopppsSegment], Field(max_length=200)]

    @model_validator(mode="after")
    def complete_stages(self):
        if {v.stage for v in self.coverage} != STAGES:
            raise ValueError("coverage must contain all six distinct BOPPPS stages")
        return self


class Activity(StrictModel):
    id: Identifier
    label: Nonempty
    sourceRefs: Annotated[list[EvidenceRef], Field(min_length=1, max_length=100)]


class GoalLink(StrictModel):
    objectiveId: Identifier
    activityId: Identifier
    supportLevel: Literal["DIRECT", "PARTIAL"]
    reason: Nonempty
    evidenceRefs: Annotated[list[EvidenceRef], Field(min_length=1, max_length=100)]


class UnsupportedGoal(StrictModel):
    objectiveId: Identifier
    state: Literal["NO_OBSERVED_ACTIVITY", "INSUFFICIENT_INPUT"]
    reason: Nonempty


class GoalPayload(StrictModel):
    activities: Annotated[list[Activity], Field(max_length=200)]
    links: Annotated[list[GoalLink], Field(max_length=500)]
    unsupportedGoals: Annotated[list[UnsupportedGoal], Field(max_length=200)]


class Transition(StrictModel):
    fromSegmentId: Identifier
    toSegmentId: Identifier
    type: Literal["ABRUPT_TOPIC_CHANGE", "NO_OBSERVED_LINK", "REPEATED_RESTART", "UNMARKED_GAP"]
    statement: Nonempty
    evidenceRefs: Annotated[list[EvidenceRef], Field(min_length=1, max_length=100)]
    reason: Nonempty


class TransitionPayload(StrictModel):
    transitions: Annotated[list[Transition], Field(max_length=200)]


class Region(StrictModel):
    x: Annotated[float, Field(ge=0, le=1)]
    y: Annotated[float, Field(ge=0, le=1)]
    width: Annotated[float, Field(gt=0, le=1)]
    height: Annotated[float, Field(gt=0, le=1)]

    @model_validator(mode="after")
    def within_image(self):
        if self.x + self.width > 1 or self.y + self.height > 1:
            raise ValueError("region exceeds image")
        return self


class ImageBlock(StrictModel):
    id: Identifier
    text: Nonempty
    region: Region | None = None


class ImagePayload(StrictModel):
    readability: Literal["READABLE", "PARTIAL", "UNREADABLE"]
    blocks: Annotated[list[ImageBlock], Field(max_length=500)]
    limitations: Annotated[list[Nonempty], Field(max_length=30)]


class MaterialSupport(StrictModel):
    imageBlockId: Identifier
    topicId: Identifier | None = None
    knowledgePointId: Identifier | None = None
    relation: Literal["SUPPORTS", "PARTIAL", "MISMATCH"]
    reason: Nonempty
    evidenceRefs: Annotated[list[EvidenceRef], Field(min_length=2, max_length=100)]

    @model_validator(mode="after")
    def target(self):
        if (self.topicId is None) == (self.knowledgePointId is None):
            raise ValueError("exactly one topicId or knowledgePointId required")
        return self


class MaterialConcern(StrictModel):
    type: Literal["DENSE_CONTENT", "MISSING_KEY_POINT", "POSSIBLE_MISMATCH"]
    statement: Nonempty
    evidenceRefs: Annotated[list[EvidenceRef], Field(min_length=1, max_length=100)]
    ruleOrReason: Nonempty


class MaterialPayload(StrictModel):
    supports: Annotated[list[MaterialSupport], Field(max_length=200)]
    concerns: Annotated[list[MaterialConcern], Field(max_length=100)]


class Word(StrictModel):
    text: Nonempty
    startMs: Annotated[int, Field(ge=0)]
    endMs: Annotated[int, Field(gt=0)]


class TranscriptSegment(StrictModel):
    segmentId: Identifier
    mediaStartMs: Annotated[int, Field(ge=0)]
    mediaEndMs: Annotated[int, Field(gt=0)]
    text: Nonempty
    speakerRole: Literal["UNKNOWN", "TEACHER", "STUDENT"] = "UNKNOWN"
    roleSource: Literal["UNKNOWN", "MANUAL"] = "UNKNOWN"
    words: Annotated[list[Word], Field(max_length=20000)] = Field(default_factory=list)
    classRanges: list[dict[str, int]] = Field(default_factory=list)
    mappingQuality: Literal["UNKNOWN", "EXACT", "ESTIMATED"] = "UNKNOWN"

    @model_validator(mode="after")
    def times(self):
        if self.mediaEndMs <= self.mediaStartMs:
            raise ValueError("invalid segment interval")
        for word in self.words:
            if not self.mediaStartMs <= word.startMs < word.endMs <= self.mediaEndMs:
                raise ValueError("word outside segment")
        for interval in self.classRanges:
            if set(interval) != {"startMs", "endMs"} or not 0 <= interval["startMs"] < interval["endMs"]:
                raise ValueError("invalid classroom interval")
        return self


class TranscriptPayload(StrictModel):
    schemaVersion: Literal[1] = 1
    timingSource: Literal["PROVIDER"] = "PROVIDER"
    segments: Annotated[list[TranscriptSegment], Field(max_length=30000)]


PAYLOAD_MODELS = {"CONTENT": ContentPayload, "SYLLABUS_ALIGNMENT": SyllabusPayload,
                  "BOPPPS": BopppsPayload, "GOAL_ACTIVITY": GoalPayload,
                  "TRANSITION": TransitionPayload, "IMAGE_TEXT": ImagePayload,
                  "MATERIAL_SUPPORT": MaterialPayload, "TRANSCRIBE": TranscriptPayload}


class Candidate(StrictModel):
    resultState: ResultState
    payload: dict[str, Any]
    limitations: Annotated[list[Nonempty], Field(max_length=30)]


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def validate_candidate(operation: str, candidate: dict) -> dict:
    envelope = Candidate.model_validate(candidate)
    payload = PAYLOAD_MODELS[operation].model_validate(envelope.payload).model_dump()
    if envelope.resultState != "SUPPORTED" and not envelope.limitations:
        raise ValueError("non-supported results require limitations")
    if operation == "IMAGE_TEXT":
        if payload["readability"] == "UNREADABLE" and payload["blocks"]:
            raise ValueError("unreadable image cannot contain invented text")
        if payload["readability"] == "UNREADABLE" and envelope.resultState != "UNREADABLE":
            raise ValueError("unreadable status mismatch")
    content_keys = {"CONTENT": ["topics"], "TRANSCRIBE": ["segments"], "IMAGE_TEXT": ["blocks"],
                    "SYLLABUS_ALIGNMENT": ["alignments", "deviations"], "BOPPPS": ["segments"],
                    "GOAL_ACTIVITY": ["activities", "unsupportedGoals"], "TRANSITION": ["transitions"],
                    "MATERIAL_SUPPORT": ["supports", "concerns"]}
    # An empty model answer is never silently promoted to a successful teaching fact.
    if envelope.resultState in {"SUPPORTED", "PARTIAL"} and not any(payload.get(k) for k in content_keys[operation]):
        raise ValueError("SUPPORTED/PARTIAL requires substantive output; use INSUFFICIENT_INPUT")
    for key in ("topics", "segments", "activities", "blocks"):
        rows = payload.get(key, [])
        ids = [r.get("id", r.get("segmentId")) for r in rows]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate output item identifier")
    return {"resultState": envelope.resultState, "payload": payload, "limitations": envelope.limitations}
