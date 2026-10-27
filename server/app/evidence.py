from __future__ import annotations

import re
from dataclasses import dataclass

from .errors import ServiceError
from .schemas import AnalysisRequest, ImageBlock, STAGES, TranscriptSegment

TEXT_KEYS = {"text", "label", "name", "description", "note", "notes", "statement", "reason"}
COLLECTION_KEYS = {"segments", "knowledgePoints", "objectives", "events", "facts", "blocks", "topics", "activities", "records"}
TRANSCRIPT_KINDS = {"TRANSCRIPT", "TRANSCRIPT_SEGMENT"}
PHASE_KINDS = {"PHASES", "MANUAL_PHASE", "BOPPPS"}
ACTUAL_ACTIVITY_KINDS = TRANSCRIPT_KINDS | PHASE_KINDS | {"EVENTS", "INTERACTION_EVENT", "FACTS", "MANUAL_FACT"}


def normalize(text: str) -> str:
    return re.sub(r"\s+", "", text)


@dataclass
class Source:
    input_id: str
    key: str | None
    kind: str
    text: str
    row: dict


class EvidenceIndex:
    def __init__(self, request: AnalysisRequest):
        self.request = request
        self.sources: dict[tuple[str, str | None], Source] = {}
        self.knowledge_ids: set[str] = set()
        self.objective_ids: set[str] = set()
        self.topic_ids: set[str] = set()
        self.block_ids: set[str] = set()
        self.phase_ids: set[str] = set()
        self.context = {}
        for item in request.inputs:
            payload = item.payload
            if item.kind == "CONTEXT":
                context = payload.get("context", payload)
                if not isinstance(context, dict): raise ServiceError("INVALID_INPUT", "覆盖上下文必须是对象")
                self.context.update(context)
                # Coverage metadata has no Room evidence source and cannot prove a classroom claim.
                continue
            elif isinstance(payload.get("context"), dict):
                self.context.update(payload["context"])
            seen = set()
            for collection in COLLECTION_KEYS:
                rows = payload.get(collection, [])
                if not isinstance(rows, list) or len(rows) > 30000:
                    raise ServiceError("INVALID_INPUT", f"{collection}必须是有界列表")
                for row in rows:
                    if not isinstance(row, dict):
                        raise ServiceError("INVALID_INPUT", "来源条目必须为对象")
                    key = row.get("segmentId", row.get("id"))
                    if not isinstance(key, str) or not key or key in seen:
                        raise ServiceError("INVALID_INPUT", "来源条目缺少唯一id/segmentId")
                    seen.add(key)
                    if item.kind in TRANSCRIPT_KINDS and collection == "segments":
                        try:
                            TranscriptSegment.model_validate(row)
                        except ValueError as exc:
                            raise ServiceError("INVALID_INPUT", "转写片段字段或时间无效") from exc
                    if collection in {"knowledgePoints", "objectives", "blocks"} and (not isinstance(row.get("text"), str) or not row["text"].strip()):
                        raise ServiceError("INVALID_INPUT", "课程要求和图片文字不能为空")
                    if item.kind == "IMAGE_TEXT" and collection == "blocks":
                        try: ImageBlock.model_validate(row)
                        except ValueError as exc: raise ServiceError("INVALID_INPUT", "图片识别块格式不合法") from exc
                    if item.kind in PHASE_KINDS and "startMs" in row:
                        start, end = row.get("startMs"), row.get("endMs")
                        if type(start) is not int or type(end) is not int or not 0 <= start < end:
                            raise ServiceError("INVALID_INPUT", "环节时间区间无效")
                    text = "\n".join(v for k, v in row.items() if k in TEXT_KEYS and isinstance(v, str))
                    if len(text) > 200000:
                        raise ServiceError("INVALID_INPUT", "来源文本超过上限")
                    self.sources[(item.inputId, key)] = Source(item.inputId, key, item.kind, text, row)
                    if collection == "knowledgePoints": self.knowledge_ids.add(key)
                    if collection == "objectives": self.objective_ids.add(key)
                    if collection == "topics": self.topic_ids.add(key)
                    if collection == "blocks": self.block_ids.add(key)
                    if collection == "segments" and item.kind in PHASE_KINDS: self.phase_ids.add(key)
            root_text = "\n".join(v for k, v in payload.items() if k in TEXT_KEYS and isinstance(v, str))
            self.sources[(item.inputId, None)] = Source(item.inputId, None, item.kind, root_text, payload)
            if item.kind == "COURSE_REFERENCE":
                for selection, rows in (("selectedKnowledgePointIds", payload.get("knowledgePoints", [])), ("selectedObjectiveIds", payload.get("objectives", []))):
                    if selection in payload:
                        selected = payload[selection]
                        ids = {v["id"] for v in rows}
                        if not isinstance(selected, list) or not all(isinstance(v, str) and v in ids for v in selected):
                            raise ServiceError("INVALID_INPUT", "选定课程要求必须属于所传版本")
                        target = self.knowledge_ids if selection == "selectedKnowledgePointIds" else self.objective_ids
                        target.difference_update(ids - set(selected))

    def refs(self, refs: list[dict]) -> list[Source]:
        result = []
        for ref in refs:
            source = self.sources.get((ref["inputId"], ref.get("itemKey")))
            if source is None:
                raise ServiceError("INVALID_EVIDENCE", "引用不属于本次输入白名单")
            quote = ref.get("quote")
            # Match Android's row-level text fields; do not splice label/note into a fabricated quote.
            if quote is not None and (not normalize(quote) or not any(
                normalize(quote) in normalize(value)
                for key, value in source.row.items() if key in TEXT_KEYS and isinstance(value, str)
            )):
                raise ServiceError("INVALID_EVIDENCE", "引文与对应来源不匹配")
            result.append(source)
        return result

    def validate_all_refs(self, value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in {"evidenceRefs", "sourceRefs"}: self.refs(item)
                else: self.validate_all_refs(item)
        elif isinstance(value, list):
            for item in value: self.validate_all_refs(item)

    @property
    def has_transcript(self):
        return any(s.kind in TRANSCRIPT_KINDS and s.key is not None and s.text.strip() for s in self.sources.values())

    def missing_requirements(self) -> str | None:
        op = self.request.operation
        if op in {"IMAGE_TEXT", "TRANSCRIBE"}: return None
        if not self.has_transcript: return "缺少可用的真实课堂转写"
        if op == "SYLLABUS_ALIGNMENT" and not self.knowledge_ids: return "未提供本次选定知识点"
        if op == "GOAL_ACTIVITY" and not self.objective_ids: return "未提供本次选定教学目标"
        if op == "TRANSITION" and len(self.phase_ids) < 2: return "缺少两段有效教学环节"
        if op == "TRANSITION" and not any(s.kind in TRANSCRIPT_KINDS and s.row.get("classRanges") and s.row.get("mappingQuality") in {"EXACT", "ESTIMATED"} for s in self.sources.values()):
            return "转写缺少可靠课堂时间映射，不能定位教学环节前后讲授"
        if op == "MATERIAL_SUPPORT" and (not self.block_ids or not (self.topic_ids or self.knowledge_ids)):
            return "缺少真实图片识别块或对应知识点"
        return None

    def coverage_sufficient(self) -> bool:
        c = self.context
        duration = c.get("classDurationMs")
        if type(duration) is not int or duration <= 0 or c.get("allAudioTranscribed") is not True or c.get("missingAudioIds") != [] or not self.has_transcript:
            return False
        spans = c.get("recordingSpans", [])
        if not isinstance(spans, list): return False
        intervals = []
        for span in spans:
            if not isinstance(span, dict) or span.get("mappingQuality") not in {"EXACT", "ESTIMATED"}: continue
            a, b = span.get("classStartMs"), span.get("classEndMs")
            if type(a) is int and type(b) is int and 0 <= a < b <= duration: intervals.append((a, b))
        end, covered = 0, 0
        for start, finish in sorted(intervals):
            covered += max(0, finish - max(end, start))
            end = max(end, finish)
        return covered / duration >= 0.9

    def validate_result(self, payload: dict) -> None:
        self.validate_all_refs(payload)
        op = self.request.operation
        if op == "CONTENT":
            for item in payload["topics"]:
                if not any(s.kind in TRANSCRIPT_KINDS and s.key is not None for s in self.refs(item["evidenceRefs"])):
                    raise ServiceError("INVALID_EVIDENCE", "主题必须引用真实转写，课程要求不能证明已经讲授")
        elif op == "SYLLABUS_ALIGNMENT":
            ids = [a["knowledgePointId"] for a in payload["alignments"]]
            if set(ids) != self.knowledge_ids or len(ids) != len(set(ids)):
                raise ServiceError("INVALID_EVIDENCE", "大纲对齐必须逐项覆盖选定知识点")
            scope = payload["coverageScope"]
            transcript_ids = {i.inputId for i in self.request.inputs if i.kind in TRANSCRIPT_KINDS}
            if set(scope["selectedKnowledgePointIds"]) != self.knowledge_ids or not set(scope["transcriptVersionIds"]) <= transcript_ids:
                raise ServiceError("INVALID_EVIDENCE", "覆盖范围不属于本次输入")
            for item in payload["alignments"]:
                if item["coverage"] == "COVERED" and not any(s.kind in TRANSCRIPT_KINDS for s in self.refs(item["evidenceRefs"])):
                    raise ServiceError("INVALID_EVIDENCE", "已覆盖结论必须有实际转写")
                if item["coverage"] == "POSSIBLY_MISSING" and not self.coverage_sufficient():
                    item["coverage"] = "INSUFFICIENT_INPUT"
                    item["reason"] = "录音覆盖不足，不能把未识别到视为未讲授。"
            for deviation in payload["deviations"]:
                if not any(s.kind in TRANSCRIPT_KINDS for s in self.refs(deviation["evidenceRefs"])):
                    raise ServiceError("INVALID_EVIDENCE", "偏离结论必须引用实际讲授")
        elif op == "BOPPPS":
            segments = {s["id"]: s for s in payload["segments"]}
            for segment in segments.values():
                if not any(s.kind in ACTUAL_ACTIVITY_KINDS for s in self.refs(segment["evidenceRefs"])):
                    raise ServiceError("INVALID_EVIDENCE", "BOPPPS候选必须引用真实课堂来源")
            for coverage in payload["coverage"]:
                ids = coverage["segmentIds"]
                if any(i not in segments or segments[i]["stage"] != coverage["stage"] for i in ids):
                    raise ServiceError("INVALID_EVIDENCE", "BOPPPS覆盖引用了错误候选段")
                if (coverage["status"] == "OBSERVED") != bool(ids):
                    raise ServiceError("INVALID_EVIDENCE", "观察状态与候选段不一致")
                if coverage["status"] == "POSSIBLY_MISSING" and not self.coverage_sufficient():
                    coverage.update(status="INSUFFICIENT_INPUT", reason="采集覆盖不足，无法判断该环节是否缺失。")
        elif op == "GOAL_ACTIVITY":
            activities = {a["id"]: a for a in payload["activities"]}
            for activity in activities.values():
                if not any(s.kind in ACTUAL_ACTIVITY_KINDS for s in self.refs(activity["sourceRefs"])):
                    raise ServiceError("INVALID_EVIDENCE", "活动不能由课程要求替代真实课堂来源")
            goals = set()
            for link in payload["links"]:
                if link["objectiveId"] not in self.objective_ids or link["activityId"] not in activities:
                    raise ServiceError("INVALID_EVIDENCE", "目标或活动不存在")
                activity_refs = {(r["inputId"], r.get("itemKey")) for r in activities[link["activityId"]]["sourceRefs"]}
                if not any((s.input_id, s.key) in activity_refs and s.kind in ACTUAL_ACTIVITY_KINDS for s in self.refs(link["evidenceRefs"])):
                    raise ServiceError("INVALID_EVIDENCE", "目标关联必须引用该活动的实际来源")
                goals.add(link["objectiveId"])
            for missing in payload["unsupportedGoals"]:
                if missing["objectiveId"] not in self.objective_ids or missing["objectiveId"] in goals:
                    raise ServiceError("INVALID_EVIDENCE", "无活动目标与关联冲突")
                goals.add(missing["objectiveId"])
                if missing["state"] == "NO_OBSERVED_ACTIVITY" and not self.coverage_sufficient():
                    missing.update(state="INSUFFICIENT_INPUT", reason="输入覆盖不足，无法确认未发生目标活动。")
            if goals != self.objective_ids: raise ServiceError("INVALID_EVIDENCE", "必须说明每个选定目标")
        elif op == "TRANSITION":
            phases = sorted((s for s in self.sources.values() if s.kind in PHASE_KINDS and s.key and "startMs" in s.row), key=lambda s: s.row["startMs"])
            pairs = {(a.key, b.key): (a, b) for a, b in zip(phases, phases[1:])}
            for transition in payload["transitions"]:
                pair = pairs.get((transition["fromSegmentId"], transition["toSegmentId"]))
                if not pair: raise ServiceError("INVALID_EVIDENCE", "衔接只能引用相邻有效环节")
                sources = self.refs(transition["evidenceRefs"])
                if transition["type"] == "UNMARKED_GAP":
                    if pair[1].row["startMs"] <= pair[0].row["endMs"]:
                        raise ServiceError("INVALID_EVIDENCE", "相邻环节没有未标注空档")
                else:
                    transcripts = [s for s in sources if s.kind in TRANSCRIPT_KINDS]
                    if len({(s.input_id, s.key) for s in transcripts}) < 2:
                        raise ServiceError("INVALID_EVIDENCE", "语义衔接需要前后两处真实文本")
                    def intersects(source, start, end):
                        return source.row.get("mappingQuality") in {"EXACT", "ESTIMATED"} and any(r["startMs"] < end and r["endMs"] > start for r in source.row.get("classRanges", []))
                    before_end, after_start = pair[0].row["endMs"], pair[1].row["startMs"]
                    before = [s for s in transcripts if intersects(s, max(pair[0].row["startMs"], before_end - 60000), before_end)]
                    after = [s for s in transcripts if intersects(s, after_start, min(pair[1].row["endMs"], after_start + 60000))]
                    if not any((a.input_id, a.key) != (b.input_id, b.key) for a in before for b in after):
                        raise ServiceError("INVALID_EVIDENCE", "衔接引用不在前后环节的真实讲授窗口")
        elif op == "MATERIAL_SUPPORT":
            for support in payload["supports"]:
                if support["imageBlockId"] not in self.block_ids or (support["topicId"] and support["topicId"] not in self.topic_ids) or (support["knowledgePointId"] and support["knowledgePointId"] not in self.knowledge_ids):
                    raise ServiceError("INVALID_EVIDENCE", "图片块或知识点不在输入范围")
                sources = self.refs(support["evidenceRefs"])
                if not any(s.kind == "IMAGE_TEXT" and s.key == support["imageBlockId"] for s in sources) or not any(s.kind in TRANSCRIPT_KINDS for s in sources):
                    raise ServiceError("INVALID_EVIDENCE", "支撑关系须同时引用图片内容和真实讲授")
            for concern in payload["concerns"]:
                sources = self.refs(concern["evidenceRefs"])
                if not any(s.kind == "IMAGE_TEXT" for s in sources):
                    raise ServiceError("INVALID_EVIDENCE", "材料问题必须引用实际图片内容")
                if concern["type"] in {"MISSING_KEY_POINT", "POSSIBLE_MISMATCH"} and not any(s.kind in TRANSCRIPT_KINDS for s in sources):
                    raise ServiceError("INVALID_EVIDENCE", "材料缺失或不匹配候选必须有实际讲授依据")
                if concern["type"] == "MISSING_KEY_POINT" and not any(s.kind in {"COURSE_REFERENCE", "CONTENT"} for s in sources):
                    raise ServiceError("INVALID_EVIDENCE", "缺少关键内容候选必须引用本次目标或知识点")


def insufficient_payload(operation: str, reason: str) -> dict:
    payloads = {
        "CONTENT": {"topics": []}, "SYLLABUS_ALIGNMENT": {"alignments": [], "deviations": [], "coverageScope": {"selectedKnowledgePointIds": [], "transcriptVersionIds": [], "missingAudioIds": []}},
        "BOPPPS": {"coverage": [{"stage": s, "status": "INSUFFICIENT_INPUT", "segmentIds": [], "reason": reason} for s in sorted(STAGES)], "segments": []},
        "GOAL_ACTIVITY": {"activities": [], "links": [], "unsupportedGoals": []},
        "TRANSITION": {"transitions": []}, "MATERIAL_SUPPORT": {"supports": [], "concerns": []},
        "TRANSCRIBE": {"schemaVersion": 1, "timingSource": "PROVIDER", "segments": []},
        "IMAGE_TEXT": {"readability": "UNREADABLE", "blocks": [], "limitations": [reason]},
    }
    return {"resultState": "INSUFFICIENT_INPUT", "payload": payloads[operation], "limitations": [reason]}
