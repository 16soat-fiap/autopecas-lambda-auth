package br.com.autopecas.application;

import br.com.autopecas.domain.FuncionarioRepository;
import br.com.autopecas.domain.validation.CpfValidator;
import br.com.autopecas.infrastructure.security.TokenService;
import java.util.Optional;

public class FuncionarioAuthService {
    private final FuncionarioRepository funcionarios;
    private final TokenService tokens;

    public FuncionarioAuthService(FuncionarioRepository funcionarios, TokenService tokens) {
        this.funcionarios = funcionarios;
        this.tokens = tokens;
    }

    public Optional<String> autenticar(String cpf) {
        if (!CpfValidator.isValid(cpf)) return Optional.empty();
        var funcionario = funcionarios.buscarPorCpf(CpfValidator.normalize(cpf));
        if (funcionario.isEmpty() || !funcionario.get().ativo()
                || funcionario.get().roles().isEmpty()
                || !TokenService.STAFF_ROLES.containsAll(funcionario.get().roles())) return Optional.empty();
        return Optional.of(tokens.gerarFuncionario(funcionario.get().id(), funcionario.get().roles()));
    }
}
