import type { CatalogEntryDto, SourceFileDto } from '../api/types';

// Catalog statements and source files shared by the tests of the code and context zones.

export const SHA = '3858f9c630cf989bb6809a86edf47c2be78dc9f1';
export const OWNER_PATH =
  'spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java';
export const OWNER_SOURCE = Array.from({ length: 120 }, (_, i) => `line ${i + 1}`).join('\n');

export function entry(overrides: Partial<CatalogEntryDto> = {}): CatalogEntryDto {
  return {
    statementId: 'stmt-1',
    logicalId: 'logical-1',
    codeUnit: { type: 'project', name: 'spring-petclinic-microservices', version: SHA },
    module: 'spring-petclinic-customers-service',
    service: 'customers-service',
    filePath: OWNER_PATH,
    fileId: 'file-owner',
    packageName: 'org.springframework.samples.petclinic.customers.web',
    classFqn: 'org.springframework.samples.petclinic.customers.web.OwnerResource',
    classBinary: 'org.springframework.samples.petclinic.customers.web.OwnerResource',
    methodName: 'updateOwner',
    methodSignature: 'updateOwner(int,OwnerRequest)',
    methodId: 'method-1',
    inLambda: false,
    line: 89,
    endLine: 89,
    column: 9,
    methodStartLine: 84,
    methodEndLine: 92,
    loggingApi: 'slf4j',
    detection: 'typed',
    loggerExpr: 'log',
    loggerName: 'org.springframework.samples.petclinic.customers.web.OwnerResource',
    loggerNameKind: 'class_literal',
    level: 'INFO',
    levelDynamic: false,
    templateRaw: '"Saving owner {}"',
    template: 'Saving owner {}',
    templateKind: 'placeholders',
    unsupportedReason: null,
    constantTokens: ['saving', 'owner'],
    literalLength: 13,
    placeholderCount: 1,
    hasThrowableArg: false,
    enclosing: null,
    control: null,
    snippet: 'line 86\nline 87\nline 88\nline 89\nline 90',
    snippetStartLine: 86,
    githubUrl: `https://github.com/spring-petclinic/spring-petclinic-microservices/blob/${SHA}/${OWNER_PATH}#L89`,
    analyzerVersion: '0.1.0',
    analyzedAt: null,
    ...overrides,
  };
}

export const source = (fileId: string, content: string, filePath = OWNER_PATH): SourceFileDto => ({
  fileId,
  codeUnit: { type: 'project', name: 'spring-petclinic-microservices', version: SHA },
  module: null,
  filePath,
  content,
  lineCount: content.split('\n').length,
});
