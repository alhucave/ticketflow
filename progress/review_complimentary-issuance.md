# Review — feature F-021 (complimentary-issuance)

**Veredicto:** APPROVED

Verificado de forma independiente: `./init.sh` verde (BUILD SUCCESSFUL, JaCoCo >= 90%) e `INCLUDE_INTEGRATION=true ./init.sh` verde con Colima (IssueComplimentaryUseCaseIT 12/12 y ComplimentaryApiEndToEndIT 6/6, 0 skipped).

## Criterios de aceptación
- Emisión AVAILABLE -> COMPLIMENTARY atómica, nunca contada como venta: IssueComplimentaryUseCaseIT (`execute_issue_movesAvailableToComplimentaryAndSoldStaysZero`, `..._storesComplimentaryOrderAndAuditEntry...`), ComplimentaryApiEndToEndIT — [x]
- No supera el disponible (409): IssueComplimentaryUseCaseIT `cannotExceedAvailable...`, ComplimentaryControllerTest `issue_insufficientInventory_is409` — [x]
- Idempotencia / replay / payload distinto (409) / sin colisión con compras: IssueComplimentaryUseCaseTest (replay*), IssueComplimentaryUseCaseIT (`sameKey*`, `sameKeyAsAPurchase_doesNotCollide`, 20 en paralelo) — [x]
- Solo admin, seguro por defecto: AdminKeyGuardTest, ComplimentaryControllerTest (401, método distinto, precede a validación), ComplimentaryDisabledWebTest (403), ComplimentaryApiEndToEndIT `adminKeyEnforcedEndToEnd` — [x]
- Nada sale de COMPLIMENTARY (barrido, ProcessOrder, releaseReservation, matriz): IssueComplimentaryUseCaseIT `sweepAndProcessing_neverTouchComplimentaryOrders`, `releaseReservation_onComplimentaryOrder_isRejected...` — [x]
- Códigos HTTP 201/400/401/403/404/409 cubiertos: ComplimentaryControllerTest + ComplimentaryDisabledWebTest — [x]

## Checkpoints
- C1: [x] ambos `init.sh` verdes, ejecutados por el reviewer.
- C2: [x] `domain` sin Spring/AWS (Order, OrderId, puerto solo Reactor); web/persistence en infrastructure; use case sin anotaciones, bean en UseCaseConfig.
- C3: [x] ver arriba; tests con aserciones reales (estado de inventario, orden, auditoría).
- C4: [x] sin `.block()`/`Thread.sleep` en `src/main`; tests usan Awaitility, sin sleeps fijos.
- C5: [x] `moveInventory` con `available >= qty` y `version+1`.
- C6: [x] transición AVAILABLE -> COMPLIMENTARY validada por la matriz (OrderAuditEntry construida antes de tocar DynamoDB) y auditada con actor y reason.
- C7: [x] inglés, sin Lombok ni `@Autowired` en campos.
- C8: [x] sin secretos: ver análisis abajo.
- C9: [x] JaCoCo >= 90% superado.
- C10: [x] scope acotado (la relajación de `Order` y docs son necesarios para la feature).
- C11: [x] README, docs/architecture.md, .env.example actualizados.
- C12: [x] adaptadores reales (DynamoDB Local + LocalStack SQS) en IssueComplimentaryUseCaseIT y ComplimentaryApiEndToEndIT. `issueComplimentary` es un único `TransactWriteItems` [0] inventario `available -> complimentary` condicionado `available >= qty` + `version+1`, [1] Put orden `attribute_not_exists(orderId)` con ALL_OLD, [2] Put auditoría; mismo layout que `placeReservation`, por lo que `translatePlacement` mapea correctamente (orden existente -> `OrderAlreadyExistsException` que el caso de uso convierte en replay; inventario -> Insufficient/EventNotFound). Compatible con la llamada del caso de uso (probado con 20 paralelas y concurrencia con compras sin sobreventa).

## Puntos específicos verificados
- **Comparación en tiempo constante**: `AdminKeyGuard` hashea ambos valores a SHA-256 y usa `MessageDigest.isEqual`; calcula siempre aunque falte cabecera (longitud no se filtra). Correcto.
- **Seguro por defecto**: clave null/blank -> `configuredDigest == null` -> 403 siempre, incluso con cabecera coincidente/vacía (test parametrizado). `application.yml` default vacío, compose `${ADMIN_API_KEY:-}`.
- **Mensajes fijos**: excepción y ProblemDetail con texto fijo; sin eco de clave ni valor recibido (tests `messages_neverEcho...`, `wrongAdminKey_is401WithFixedMessage`). Sin logging en filtro/guard/caso de uso (grep).
- **Clave fuera de repo**: grep de todo el árbol: no hay valor real; solo el ejemplo explícitamente de desarrollo `dev-only-change-me` en README/.env.example como comentario (no se carga por defecto en ningún sitio); `.env` en `.gitignore` (`git check-ignore` confirma) y solo `.env.example` versionado, con `ADMIN_API_KEY=` vacío. Tests usan `test-admin-key` ficticia.
- **Filtro**: cubre cualquier método en `/events/{id}/complimentary` y se ejecuta antes de validación/búsqueda de evento (sin fuga de existencia); render por `ProblemWebExceptionHandler`.
- **Namespace de idempotencia**: `ticketflow:complimentary:` vs `ticketflow:order:`; test unitario y IT con misma clave sin colisión.
- **Nada cuenta/libera/mueve una cortesía**: contador `complimentary` separado de `sold`; `releaseReservation` y la matriz la rechazan; GSI de expiración solo consulta RESERVED/PENDING_CONFIRMATION; `ProcessOrderUseCase` -> `AlreadyProcessed`. Cubierto por IT.
- **Invariante relajado de `Order`**: `>= createdAt` solo para COMPLIMENTARY; el resto sigue `>`. Seguro: la orden COMPLIMENTARY no entra en el barrido por estado; `transitionTo(COMPLIMENTARY)` fija expiry = createdAt. Cubierto en OrderTest.
- **Replay**: 201 con mismo body/Location; payload (evento, cantidad, reason vía auditoría) distinto -> 409; orden no COMPLIMENTARY -> 409; orden desaparecida -> IllegalState (500 fijo). Coherente y probado.
- **Pureza de dominio y sin `.block()`**: confirmados.

## Preocupaciones señaladas por el implementer
1. **401 vs 403 (fuga de "deshabilitado")**: aceptable. Está documentado (README, architecture, informe), solo revela que el servidor no tiene clave (config, no secreto), y el diseño pidió secure-by-default observable.
2. **Actor fijo `complimentary-issuance`**: aceptable y documentado; no existe autenticación de usuarios en el proyecto. Limitación de trazabilidad a resolver cuando haya identidad.
3. **Sin rate limiting en la ruta admin (fuerza bruta)**: aceptable por ahora y documentado como pendiente de la fase de rate limit; el filtro está en `ADMIN_ROUTES` y las excepciones de filtros ya pasan por `ProblemWebExceptionHandler`, así que el limiter encaja. Recomendado asegurarlo antes de exponer la ruta públicamente.

## Cambios requeridos
Ninguno.

## Observaciones no bloqueantes
- Usar un ejemplo `dev-only-change-me` en README invita a claves débiles; considerar sugerir `openssl rand -hex 32`.
- La ruta admin comparte el actor fijo; al llegar la identidad real, sustituirlo.
- Añadir rate limiting específico para `X-Admin-Key` fallidas en la fase de rate limit.
