package com.bigdata.modules.metadata.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateCommentDTO {

    @NotBlank(message = "注释内容不能为空")
    private String comment;

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
