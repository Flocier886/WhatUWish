package com.flocier.trigger.http.admin;

import com.flocier.api.response.Response;
import com.flocier.domain.ledger.model.entity.LedgerFactEntity;
import com.flocier.domain.ledger.model.entity.LedgerFactQueryEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunQueryEntity;
import com.flocier.domain.ledger.model.entity.LedgerRunView;
import com.flocier.domain.ledger.model.entity.RunLedgerEntity;
import com.flocier.domain.ledger.model.vo.FactType;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.domain.ledger.service.LedgerQueryService;
import com.flocier.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/ledger")
public class LedgerFactAdminController {

    @Resource
    private LedgerFactService ledgerFactService;
    @Resource
    private LedgerQueryService ledgerQueryService;

    @GetMapping("/facts")
    public Response<List<LedgerFactEntity>> queryFacts(@RequestParam(required = false) String sessionId,
                                                       @RequestParam(required = false) String traceId,
                                                       @RequestParam(required = false) String requestId,
                                                       @RequestParam(required = false) String sourceType,
                                                       @RequestParam(required = false) String factType,
                                                       @RequestParam(required = false) Integer stepNo,
                                                       @RequestParam(required = false, defaultValue = "100") Integer limit) {
        FactType type = StringUtils.hasText(factType) ? FactType.valueOf(factType) : null;
        List<LedgerFactEntity> data = ledgerFactService.query(LedgerFactQueryEntity.builder()
                .sessionId(sessionId)
                .traceId(traceId)
                .requestId(requestId)
                .sourceType(sourceType)
                .factType(type)
                .stepNo(stepNo)
                .limit(limit)
                .build());
        return Response.<List<LedgerFactEntity>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("查询成功")
                .data(data)
                .build();
    }

    @GetMapping("/runs")
    public Response<List<RunLedgerEntity>> queryRuns(@RequestParam(required = false) String sessionId,
                                                     @RequestParam(required = false) String traceId,
                                                     @RequestParam(required = false) String runId,
                                                     @RequestParam(required = false, defaultValue = "100") Integer limit) {
        List<RunLedgerEntity> data = ledgerQueryService.queryRuns(LedgerRunQueryEntity.builder()
                .sessionId(sessionId)
                .traceId(traceId)
                .runId(runId)
                .limit(limit)
                .build());
        return Response.<List<RunLedgerEntity>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("查询成功")
                .data(data)
                .build();
    }

    @GetMapping("/run-view")
    public Response<LedgerRunView> queryRunView(@RequestParam String runId) {
        LedgerRunView data = ledgerQueryService.queryRunView(runId).orElse(null);
        return Response.<LedgerRunView>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("查询成功")
                .data(data)
                .build();
    }
}
